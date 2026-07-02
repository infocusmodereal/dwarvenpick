package com.dwarvenpick.app.query

import com.dwarvenpick.app.auth.UserAccountService
import com.dwarvenpick.app.datasource.CreateDatasourceRequest
import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.datasource.DatasourcePoolManager
import com.dwarvenpick.app.datasource.DatasourceRegistryService
import com.dwarvenpick.app.datasource.UpsertCredentialProfileRequest
import com.dwarvenpick.app.rbac.QueryAccessPolicy
import com.dwarvenpick.app.rbac.RbacService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(
    properties = [
        "dwarvenpick.auth.password-policy.min-length=8",
        "dwarvenpick.query.max-buffered-bytes=10",
        "dwarvenpick.query.max-cell-bytes=20",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
class QueryExecutionManagerJdbcBufferLimitTests {
    @Autowired
    private lateinit var queryExecutionManager: QueryExecutionManager

    @Autowired
    private lateinit var datasourceRegistryService: DatasourceRegistryService

    @Autowired
    private lateinit var datasourcePoolManager: DatasourcePoolManager

    @Autowired
    private lateinit var userAccountService: UserAccountService

    @Autowired
    private lateinit var rbacService: RbacService

    @BeforeEach
    fun resetState() {
        userAccountService.resetState()
        rbacService.resetState()
    }

    @AfterEach
    fun cleanupPools() {
        datasourcePoolManager.evictAllPools()
    }

    @Test
    fun `jdbc extraction stops before appending row that exceeds byte budget`() {
        val datasourceId = registerPostgresDatasource()
        val actor = "tc-postgres-budget-user"
        val submitted =
            queryExecutionManager.submitQuery(
                actor = actor,
                ipAddress = "127.0.0.1",
                request =
                    QueryExecutionRequest(
                        datasourceId = datasourceId,
                        sql = "select repeat('x', 8) as first_col, repeat('y', 8) as second_col",
                    ),
                policy = defaultPolicy(),
            )

        val terminal = waitForTerminalStatus(actor, submitted.executionId)
        assertThat(terminal.status).isEqualTo(QueryExecutionStatus.SUCCEEDED.name)
        assertThat(terminal.message).contains("result buffer size limit")
        assertThat(terminal.rowLimitReached).isTrue()

        val results =
            queryExecutionManager.getQueryResults(
                actor = actor,
                isSystemAdmin = false,
                executionId = submitted.executionId,
                request = QueryResultsRequest(pageSize = 10),
            )
        assertThat(results.rows).isEmpty()
        assertThat(results.rowLimitReached).isTrue()
    }

    private fun registerPostgresDatasource(): String {
        val created =
            datasourceRegistryService.createDatasource(
                CreateDatasourceRequest(
                    name = "Postgres budget TC",
                    engine = DatasourceEngine.POSTGRESQL,
                    host = postgresContainer.host,
                    port = postgresContainer.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                    database = postgresContainer.databaseName,
                    driverId = "postgres-default",
                ),
            )
        datasourceRegistryService.upsertCredentialProfile(
            datasourceId = created.id,
            profileId = "admin-ro",
            request =
                UpsertCredentialProfileRequest(
                    username = postgresContainer.username,
                    password = postgresContainer.password,
                    description = "Testcontainers postgres profile",
                ),
        )
        return created.id
    }

    private fun defaultPolicy(): QueryAccessPolicy =
        QueryAccessPolicy(
            credentialProfile = "admin-ro",
            readOnly = false,
            maxRowsPerQuery = 5000,
            maxRuntimeSeconds = 120,
            concurrencyLimit = 2,
        )

    private fun waitForTerminalStatus(
        actor: String,
        executionId: String,
        timeoutMs: Long = 15_000,
    ): QueryExecutionStatusResponse {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            val status =
                queryExecutionManager.getExecutionStatus(
                    actor = actor,
                    isSystemAdmin = false,
                    executionId = executionId,
                )
            if (status.status in setOf("SUCCEEDED", "FAILED", "CANCELED")) {
                return status
            }
            Thread.sleep(100)
        }

        throw AssertionError("Timed out waiting for query execution '$executionId' to finish.")
    }

    companion object {
        @JvmStatic
        @Container
        val postgresContainer = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }
}
