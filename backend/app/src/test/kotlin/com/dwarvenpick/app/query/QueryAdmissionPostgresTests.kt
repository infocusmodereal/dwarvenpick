package com.dwarvenpick.app.query

import com.dwarvenpick.app.auth.AuthAuditEventStore
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
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
        "dwarvenpick.query.admission-retry-attempts=20",
        "dwarvenpick.query.admission-retry-backoff-ms=5",
        "dwarvenpick.query.max-concurrency-per-datasource=2",
        "dwarvenpick.query.max-concurrency-global=2",
    ],
)
@Testcontainers
class QueryAdmissionPostgresTests {
    @Autowired
    private lateinit var queryAdmissionRepository: QueryAdmissionRepository

    @Autowired
    private lateinit var queryAdmissionService: QueryAdmissionService

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
    private lateinit var authAuditEventStore: AuthAuditEventStore

    @Autowired
    private lateinit var queryExecutionProperties: QueryExecutionProperties

    @Autowired
    private lateinit var queryJustificationPolicy: QueryJustificationPolicy

    @Autowired
    private lateinit var queryExecutionLimitPolicy: QueryExecutionLimitPolicy

    @Autowired
    private lateinit var queryHistoryRepository: QueryHistoryRepository

    @Autowired
    private lateinit var persistedQueryResultAccessService: PersistedQueryResultAccessService

    @Autowired
    private lateinit var pageTokenCodec: QueryResultPageTokenCodec

    @Autowired
    private lateinit var applicationInstanceId: ApplicationInstanceId

    @Autowired
    private lateinit var queryLifecycleMetrics: QueryLifecycleMetrics

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
        queryHistoryRepository.clear()
        authAuditEventStore.clear()
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
                        executor.submit<String> {
                            start.await()
                            runCatching {
                                queryAdmissionService.reserve(
                                    runtimeRecord(executionId, actor = "postgres-admission-user"),
                                    actorLimit = 1,
                                )
                                "admitted"
                            }.getOrElse { exception ->
                                require(exception is QueryConcurrencyLimitException)
                                exception.scope.metricValue
                            }
                        }
                    }
            start.countDown()

            assertThat(results.map { it.get() })
                .containsExactlyInAnyOrder("admitted", QueryAdmissionScope.ACTOR.metricValue)
            assertThat(activeExecutions("postgres-admission-user")).hasSize(1)
            assertThat(queryAdmissionRepository.metadataDialect()).isEqualTo("POSTGRESQL")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `postgres datasource budget admits only configured workers across actors`() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(6)
        try {
            val results =
                (1..6).map { index ->
                    executor.submit<String> {
                        start.await()
                        runCatching {
                            queryAdmissionService.reserve(
                                runtimeRecord(
                                    executionId = "datasource-race-$index",
                                    actor = "datasource-race-actor-$index",
                                ),
                                actorLimit = 5,
                            )
                            "admitted"
                        }.getOrElse { exception ->
                            require(exception is QueryConcurrencyLimitException)
                            exception.scope.metricValue
                        }
                    }
                }
            start.countDown()

            assertThat(results.map { it.get() })
                .containsExactlyInAnyOrder(
                    "admitted",
                    "admitted",
                    "datasource",
                    "datasource",
                    "datasource",
                    "datasource",
                )
            assertThat(activeExecutionsByDatasource("admission-test")).hasSize(2)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `advisory lock contention returns bounded service unavailable without reservation`() {
        val actor = "postgres-lock-timeout-user"
        heldActorLock(actor).use {
            val startedAt = System.nanoTime()
            assertThatThrownBy {
                queryAdmissionService.reserve(runtimeRecord("postgres-timeout", actor), actorLimit = 1)
            }.isInstanceOf(QueryAdmissionUnavailableException::class.java)
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
                queryJustificationPolicy = queryJustificationPolicy,
                queryExecutionLimitPolicy = queryExecutionLimitPolicy,
                queryHistoryRepository = queryHistoryRepository,
                queryRuntimeRepository = queryRuntimeRepository,
                queryAdmissionService = queryAdmissionService,
                persistedQueryResultAccessService = persistedQueryResultAccessService,
                applicationInstanceId = applicationInstanceId,
                queryLifecycleMetrics = queryLifecycleMetrics,
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
    fun `query managers enforce datasource budget across actors and record denial evidence`() {
        val datasourceId = registerPostgresDatasource()
        val secondManager = newManager("datasource-budget-second")
        val thirdManager = newManager("datasource-budget-third")
        val running =
            listOf(
                queryExecutionManager to "datasource-actor-one",
                secondManager to "datasource-actor-two",
            )
        try {
            val submitted =
                running.mapIndexed { index, (manager, actor) ->
                    manager.submitQuery(
                        actor = actor,
                        ipAddress = "127.0.0.${index + 1}",
                        request =
                            QueryExecutionRequest(
                                datasourceId = datasourceId,
                                sql = "select pg_sleep(10), ${index + 1} as ok",
                            ),
                        policy = defaultPolicy(),
                    )
                }
            submitted.zip(running).forEach { (response, pair) ->
                waitForStatus(pair.first, pair.second, response.executionId, QueryExecutionStatus.RUNNING)
            }

            val rejection =
                assertThrows<QueryConcurrencyLimitException> {
                    thirdManager.submitQuery(
                        actor = "datasource-actor-three",
                        ipAddress = "127.0.0.3",
                        request = QueryExecutionRequest(datasourceId = datasourceId, sql = "select 3 as blocked"),
                        policy = defaultPolicy(),
                    )
                }
            assertThat(rejection.scope).isEqualTo(QueryAdmissionScope.DATASOURCE)
            assertThat(rejection)
                .hasMessage("Connection query limit reached (2). Try again after an active query finishes.")

            assertThat(activeExecutionsByDatasource(datasourceId)).hasSize(2)
            assertThat(
                meterRegistry
                    .get("dwarvenpick.query.admission.denials")
                    .tag("scope", "datasource")
                    .tag("datasourceId", datasourceId)
                    .counter()
                    .count(),
            ).isEqualTo(1.0)
            assertThat(authAuditEventStore.snapshot())
                .anySatisfy { event ->
                    assertThat(event.type).isEqualTo("query.execute")
                    assertThat(event.outcome).isEqualTo("limited")
                    assertThat(event.details["admissionScope"]).isEqualTo("datasource")
                    assertThat(event.details["credentialProfile"]).isEqualTo("read-only")
                }
            assertThat(queryHistoryRepository.list(historyFilter("datasource-actor-three"))).isEmpty()
        } finally {
            running.forEach { (manager, actor) ->
                activeExecutions(actor).forEach { execution ->
                    manager.cancelQuery(actor, isSystemAdmin = false, execution.executionId)
                    waitForTerminalStatus(manager, actor, execution.executionId)
                }
            }
            secondManager.shutdownGracefully()
            thirdManager.shutdownGracefully()
        }
    }

    @Test
    fun `query managers enforce global budget across datasources`() {
        val firstDatasourceId = registerPostgresDatasource()
        val secondDatasourceId = registerPostgresDatasource()
        val thirdDatasourceId = registerPostgresDatasource()
        val secondManager = newManager("global-budget-second")
        val thirdManager = newManager("global-budget-third")
        val running =
            listOf(
                Triple(queryExecutionManager, "global-actor-one", firstDatasourceId),
                Triple(secondManager, "global-actor-two", secondDatasourceId),
            )
        try {
            val submitted =
                running.mapIndexed { index, (manager, actor, datasourceId) ->
                    manager.submitQuery(
                        actor = actor,
                        ipAddress = "127.0.1.${index + 1}",
                        request =
                            QueryExecutionRequest(
                                datasourceId = datasourceId,
                                sql = "select pg_sleep(10), ${index + 1} as ok",
                            ),
                        policy = defaultPolicy(),
                    )
                }
            submitted.zip(running).forEach { (response, triple) ->
                waitForStatus(triple.first, triple.second, response.executionId, QueryExecutionStatus.RUNNING)
            }

            val rejection =
                assertThrows<QueryConcurrencyLimitException> {
                    thirdManager.submitQuery(
                        actor = "global-actor-three",
                        ipAddress = "127.0.1.3",
                        request = QueryExecutionRequest(datasourceId = thirdDatasourceId, sql = "select 3 as blocked"),
                        policy = defaultPolicy(),
                    )
                }
            assertThat(rejection.scope).isEqualTo(QueryAdmissionScope.GLOBAL)
            assertThat(rejection)
                .hasMessage("Dwarvenpick query capacity is currently full (2). Try again shortly.")

            assertThat(activeExecutionCount()).isEqualTo(2)
        } finally {
            running.forEach { (manager, actor, _) ->
                activeExecutions(actor).forEach { execution ->
                    manager.cancelQuery(actor, isSystemAdmin = false, execution.executionId)
                    waitForTerminalStatus(manager, actor, execution.executionId)
                }
            }
            secondManager.shutdownGracefully()
            thirdManager.shutdownGracefully()
        }
    }

    @Test
    fun `remote cancel is observed once by the owning manager`() {
        val datasourceId = registerPostgresDatasource()
        val actor = "remote-cancel-user"
        val requester = newManager("remote-cancel-requester")
        val initialObservations = observationCount(RemoteQueryControlAction.CANCEL)
        try {
            val submitted =
                queryExecutionManager.submitQuery(
                    actor = actor,
                    ipAddress = "127.0.0.1",
                    request = QueryExecutionRequest(datasourceId = datasourceId, sql = "select pg_sleep(10)"),
                    policy = defaultPolicy(),
                )
            waitForStatus(queryExecutionManager, actor, submitted.executionId, QueryExecutionStatus.RUNNING)

            val response = requester.cancelQuery(actor, isSystemAdmin = false, submitted.executionId)
            assertThat(response.message).contains("Cancellation requested on backend instance")
            queryExecutionManager.pollRemoteControlRequests()
            waitForTerminalStatus(queryExecutionManager, actor, submitted.executionId)

            assertThat(
                queryExecutionManager.getExecutionStatus(actor, isSystemAdmin = false, submitted.executionId).status,
            ).isEqualTo(QueryExecutionStatus.CANCELED.name)
            assertThat(observationCount(RemoteQueryControlAction.CANCEL)).isEqualTo(initialObservations + 1)
            queryExecutionManager.pollRemoteControlRequests()
            assertThat(observationCount(RemoteQueryControlAction.CANCEL)).isEqualTo(initialObservations + 1)
        } finally {
            requester.shutdownGracefully()
        }
    }

    @Test
    fun `remote kill preserves kill intent and terminates on the owning manager`() {
        val datasourceId = registerPostgresDatasource()
        val actor = "remote-kill-user"
        val requester = newManager("remote-kill-requester")
        val initialObservations = observationCount(RemoteQueryControlAction.KILL)
        try {
            val submitted =
                queryExecutionManager.submitQuery(
                    actor = actor,
                    ipAddress = "127.0.0.1",
                    request = QueryExecutionRequest(datasourceId = datasourceId, sql = "select pg_sleep(10)"),
                    policy = defaultPolicy(),
                )
            waitForStatus(queryExecutionManager, actor, submitted.executionId, QueryExecutionStatus.RUNNING)

            val response = requester.killQuery("remote-admin", isSystemAdmin = true, submitted.executionId)
            assertThat(response.message).contains("Kill requested on backend instance")
            queryExecutionManager.pollRemoteControlRequests()
            waitForTerminalStatus(queryExecutionManager, actor, submitted.executionId)

            assertThat(
                queryExecutionManager.getExecutionStatus(actor, isSystemAdmin = false, submitted.executionId).status,
            ).isEqualTo(QueryExecutionStatus.CANCELED.name)
            assertThat(observationCount(RemoteQueryControlAction.KILL)).isEqualTo(initialObservations + 1)
        } finally {
            requester.shutdownGracefully()
        }
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

    private fun activeExecutionsByDatasource(datasourceId: String): List<PersistedQueryRuntimeMetadataRecord> =
        queryRuntimeRepository.listActiveMetadata(
            actor = "admin",
            isSystemAdmin = true,
            datasourceId = datasourceId,
            actorFilter = null,
        )

    private fun activeExecutionCount(): Int =
        queryRuntimeRepository
            .listActiveMetadata(
                actor = "admin",
                isSystemAdmin = true,
                datasourceId = null,
                actorFilter = null,
            ).size

    private fun historyFilter(actor: String): QueryHistoryFilter =
        QueryHistoryFilter(
            actor = actor,
            isSystemAdmin = false,
            datasourceId = null,
            status = null,
            from = null,
            to = null,
            limit = 100,
            offset = 0,
            actorFilter = null,
            sortOrder = QueryHistorySortOrder.NEWEST,
        )

    private fun newManager(instanceValue: String): QueryExecutionManager {
        val requesterInstanceId = mock(ApplicationInstanceId::class.java)
        `when`(requesterInstanceId.value).thenReturn(instanceValue)
        return QueryExecutionManager(
            datasourcePoolManager = datasourcePoolManager,
            authAuditLogger = authAuditLogger,
            queryExecutionProperties = queryExecutionProperties,
            queryJustificationPolicy = queryJustificationPolicy,
            queryExecutionLimitPolicy = queryExecutionLimitPolicy,
            queryHistoryRepository = queryHistoryRepository,
            queryRuntimeRepository = queryRuntimeRepository,
            queryAdmissionService = queryAdmissionService,
            persistedQueryResultAccessService = persistedQueryResultAccessService,
            applicationInstanceId = requesterInstanceId,
            queryLifecycleMetrics = queryLifecycleMetrics,
            meterRegistry = meterRegistry,
        )
    }

    private fun observationCount(action: RemoteQueryControlAction): Long =
        meterRegistry
            .get("dwarvenpick.query.remote.control.latency")
            .tag("action", action.tagValue)
            .timer()
            .count()

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
            engine = DatasourceEngine.POSTGRESQL,
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
