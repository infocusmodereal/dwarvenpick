package com.dwarvenpick.app.query

import com.dwarvenpick.app.auth.AuthAuditLogger
import com.dwarvenpick.app.datasource.CreateDatasourceRequest
import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.datasource.DatasourcePoolManager
import com.dwarvenpick.app.datasource.DatasourceRegistryService
import com.dwarvenpick.app.datasource.UpsertCredentialProfileRequest
import com.dwarvenpick.app.rbac.QueryAccessPolicy
import com.dwarvenpick.app.runtime.ApplicationInstanceId
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

@SpringBootTest(
    properties = [
        "dwarvenpick.seed.enabled=false",
        "dwarvenpick.query.admission-timeout-seconds=1",
    ],
)
@Testcontainers
class QueryAdmissionPostgresTests {
    @Autowired
    private lateinit var queryAdmissionRepository: QueryAdmissionRepository

    @Autowired
    private lateinit var queryRuntimeRepository: QueryRuntimeRepository

    @Autowired
    private lateinit var queryExecutionManager: QueryExecutionManager

    @Autowired
    private lateinit var datasourceRegistryService: DatasourceRegistryService

    @Autowired
    private lateinit var datasourcePoolManager: DatasourcePoolManager

    @Autowired
    private lateinit var authAuditLogger: AuthAuditLogger

    @Autowired
    private lateinit var queryExecutionProperties: QueryExecutionProperties

    @Autowired
    private lateinit var queryHistoryRepository: QueryHistoryRepository

    @Autowired
    private lateinit var persistedQueryResultAccessService: PersistedQueryResultAccessService

    @Autowired
    private lateinit var pageTokenCodec: QueryResultPageTokenCodec

    @Autowired
    private lateinit var applicationInstanceId: ApplicationInstanceId

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun resetRuntime() {
        queryRuntimeRepository.clear()
    }

    @AfterEach
    fun cleanupPools() {
        datasourcePoolManager.evictAllPools()
    }

    @Test
    fun `postgres metadata admission is atomic across concurrent sessions`() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results =
                listOf("postgres-admission-1", "postgres-admission-2")
                    .map { executionId ->
                        executor.submit<QueryAdmissionResult> {
                            start.await()
                            queryAdmissionRepository.reserve(
                                runtimeRecord(executionId, actor = "postgres-admission-user"),
                                limits = QueryAdmissionLimits(actor = 1, global = 10, datasource = 10),
                            )
                        }
                    }
            start.countDown()

            assertThat(results.map { it.get() })
                .containsExactlyInAnyOrder(QueryAdmissionResult.ADMITTED, QueryAdmissionResult.LIMIT_REACHED)
            assertThat(activeExecutions("postgres-admission-user")).hasSize(1)
            assertThat(queryAdmissionRepository.metadataDialect()).isEqualTo("POSTGRESQL")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `advisory lock timeout fails as infrastructure error without reservation`() {
        val actor = "postgres-lock-timeout-user"
        heldActorLock(actor).use {
            val startedAt = System.nanoTime()
            assertThatThrownBy {
                queryAdmissionRepository.reserve(
                    runtimeRecord("postgres-timeout", actor),
                    limits = QueryAdmissionLimits(actor = 1, global = 10, datasource = 10),
                )
            }.isInstanceOf(DataAccessException::class.java)
                .isNotInstanceOf(QueryConcurrencyLimitException::class.java)
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(5))
        }

        assertThat(activeExecutions(actor)).isEmpty()
    }

    @Test
    fun `query managers sharing metadata enforce the same actor limit`() {
        val datasourceId = registerPostgresDatasource()
        val actor = "two-manager-admission-user"
        val secondManager =
            QueryExecutionManager(
                datasourcePoolManager = datasourcePoolManager,
                authAuditLogger = authAuditLogger,
                queryExecutionProperties = queryExecutionProperties,
                queryHistoryRepository = queryHistoryRepository,
                queryRuntimeRepository = queryRuntimeRepository,
                queryAdmissionRepository = queryAdmissionRepository,
                persistedQueryResultAccessService = persistedQueryResultAccessService,
                pageTokenCodec = pageTokenCodec,
                applicationInstanceId = applicationInstanceId,
                meterRegistry = meterRegistry,
            )
        try {
            val submitted =
                queryExecutionManager.submitQuery(
                    actor = actor,
                    ipAddress = "127.0.0.1",
                    request = QueryExecutionRequest(datasourceId = datasourceId, sql = "select pg_sleep(5), 1 as ok"),
                    policy = defaultPolicy(),
                )
            waitForStatus(queryExecutionManager, actor, submitted.executionId, QueryExecutionStatus.RUNNING)

            assertThatThrownBy {
                secondManager.submitQuery(
                    actor = actor,
                    ipAddress = "127.0.0.2",
                    request = QueryExecutionRequest(datasourceId = datasourceId, sql = "select 2 as blocked"),
                    policy = defaultPolicy(),
                )
            }.isInstanceOf(QueryConcurrencyLimitException::class.java)
                .hasMessage("Concurrent query limit reached (1). Cancel an active query before running another.")

            queryExecutionManager.cancelQuery(actor, isSystemAdmin = false, executionId = submitted.executionId)
            waitForTerminalStatus(queryExecutionManager, actor, submitted.executionId)
            assertThat(activeExecutions(actor)).isEmpty()
        } finally {
            secondManager.shutdownGracefully()
        }
    }

    @Test
    fun `postgres metadata admission enforces global limit across actors`() {
        val limits = QueryAdmissionLimits(actor = 5, global = 1, datasource = 5)

        assertThat(queryAdmissionRepository.reserve(runtimeRecord("global-1", "actor-1"), limits))
            .isEqualTo(QueryAdmissionResult.ADMITTED)
        assertThat(queryAdmissionRepository.reserve(runtimeRecord("global-2", "actor-2"), limits))
            .isEqualTo(QueryAdmissionResult.GLOBAL_LIMIT_REACHED)
        assertThat(queryRuntimeRepository.countActive(QueryExecutionStatus.QUEUED)).isEqualTo(1)
    }

    @Test
    fun `postgres metadata admission enforces per connection limit across actors`() {
        val limits = QueryAdmissionLimits(actor = 5, global = 10, datasource = 1)

        assertThat(queryAdmissionRepository.reserve(runtimeRecord("connection-1", "actor-1"), limits))
            .isEqualTo(QueryAdmissionResult.ADMITTED)
        assertThat(queryAdmissionRepository.reserve(runtimeRecord("connection-2", "actor-2"), limits))
            .isEqualTo(QueryAdmissionResult.DATASOURCE_LIMIT_REACHED)
        assertThat(
            queryAdmissionRepository.reserve(
                runtimeRecord("connection-3", "actor-3").copy(datasourceId = "other-connection"),
                limits,
            ),
        ).isEqualTo(QueryAdmissionResult.ADMITTED)
    }

    @Test
    fun `metadata jdbc and transaction manager share the same datasource`() {
        assertThat(jdbcTemplate.dataSource).isSameAs(dataSource)
        assertThat(transactionManager).isInstanceOf(DataSourceTransactionManager::class.java)
        assertThat((transactionManager as DataSourceTransactionManager).dataSource).isSameAs(dataSource)
    }

    private fun heldActorLock(actor: String): Connection {
        val connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        connection.autoCommit = false
        connection
            .prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")
            .use { statement ->
                statement.setInt(1, QueryAdmissionLockKey.NAMESPACE)
                statement.setInt(2, QueryAdmissionLockKey.forActor(actor))
                statement.executeQuery().use { result -> result.next() }
            }
        return connection
    }

    private fun activeExecutions(actor: String): List<PersistedQueryRuntimeMetadataRecord> =
        queryRuntimeRepository.listActiveMetadata(
            actor = actor,
            isSystemAdmin = false,
            datasourceId = null,
            actorFilter = null,
        )

    private fun registerPostgresDatasource(): String {
        val created =
            datasourceRegistryService.createDatasource(
                CreateDatasourceRequest(
                    name = "Admission PostgreSQL ${UUID.randomUUID()}",
                    engine = DatasourceEngine.POSTGRESQL,
                    host = postgres.host,
                    port = postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                    database = postgres.databaseName,
                    driverId = "postgres-default",
                ),
            )
        datasourceRegistryService.upsertCredentialProfile(
            datasourceId = created.id,
            profileId = "read-only",
            request =
                UpsertCredentialProfileRequest(
                    username = postgres.username,
                    password = postgres.password,
                    description = "Shared metadata admission test",
                ),
        )
        return created.id
    }

    private fun defaultPolicy(): QueryAccessPolicy =
        QueryAccessPolicy(
            credentialProfile = "read-only",
            readOnly = true,
            maxRowsPerQuery = 100,
            maxRuntimeSeconds = 30,
            concurrencyLimit = 1,
        )

    private fun waitForStatus(
        manager: QueryExecutionManager,
        actor: String,
        executionId: String,
        expectedStatus: QueryExecutionStatus,
    ) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            if (manager.getExecutionStatus(actor, isSystemAdmin = false, executionId).status == expectedStatus.name) {
                return
            }
            Thread.sleep(50)
        }
        throw AssertionError("Execution $executionId did not reach ${expectedStatus.name}.")
    }

    private fun waitForTerminalStatus(
        manager: QueryExecutionManager,
        actor: String,
        executionId: String,
    ) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            val status = manager.getExecutionStatus(actor, isSystemAdmin = false, executionId).status
            if (status in setOf("SUCCEEDED", "FAILED", "CANCELED")) {
                return
            }
            Thread.sleep(50)
        }
        throw AssertionError("Execution $executionId did not finish.")
    }

    private fun runtimeRecord(
        executionId: String,
        actor: String,
    ): PersistedQueryRuntimeRecord {
        val now = Instant.now()
        return PersistedQueryRuntimeRecord(
            executionId = executionId,
            actor = actor,
            ipAddress = "127.0.0.1",
            datasourceId = "admission-test",
            credentialProfile = "read-only",
            defaultSchema = null,
            justification = null,
            sql = "select 1",
            sqlRedacted = false,
            queryHash = "hash-$executionId",
            maxRowsPerQuery = 100,
            maxRuntimeSeconds = 30,
            concurrencyLimit = 1,
            scriptStatementCount = 1,
            scriptStopOnError = true,
            scriptTransactionMode = ScriptTransactionMode.AUTOCOMMIT,
            scriptStatements = emptyList(),
            status = QueryExecutionStatus.QUEUED,
            message = "Query accepted and queued.",
            errorSummary = null,
            submittedAt = now,
            startedAt = null,
            completedAt = null,
            rowLimitReached = false,
            columns = emptyList(),
            rows = emptyList(),
            lastAccessedAt = now,
            resultsExpired = false,
            cancelRequested = false,
            ownerInstanceId = "admission-test-instance",
            heartbeatAt = now,
        )
    }

    companion object {
        @JvmStatic
        @Container
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun metadataDatabase(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
