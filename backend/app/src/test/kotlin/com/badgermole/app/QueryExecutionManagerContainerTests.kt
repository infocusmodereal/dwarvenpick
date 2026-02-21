package com.badgermole.app

import com.badgermole.app.auth.UserAccountService
import com.badgermole.app.datasource.CreateDatasourceRequest
import com.badgermole.app.datasource.DatasourceEngine
import com.badgermole.app.datasource.DatasourcePoolManager
import com.badgermole.app.datasource.DatasourceRegistryService
import com.badgermole.app.datasource.UpsertCredentialProfileRequest
import com.badgermole.app.query.QueryExecutionManager
import com.badgermole.app.query.QueryExecutionRequest
import com.badgermole.app.query.QueryExecutionStatusResponse
import com.badgermole.app.query.QueryResultsRequest
import com.badgermole.app.rbac.QueryAccessPolicy
import com.badgermole.app.rbac.RbacService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager

@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "badgermole.auth.password-policy.min-length=8",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
class QueryExecutionManagerContainerTests {
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
    fun `postgres execution and pagination flow succeeds`() {
        val datasourceId = registerPostgresDatasource()
        val actor = "tc-postgres-user"

        val submitted =
            queryExecutionManager.submitQuery(
                actor = actor,
                ipAddress = "127.0.0.1",
                request =
                    QueryExecutionRequest(
                        datasourceId = datasourceId,
                        sql =
                            """
                            with recursive seq(n) as (
                                select 1
                                union all
                                select n + 1 from seq where n < 25
                            )
                            select n from seq;
                            """.trimIndent(),
                    ),
                policy = defaultPolicy(),
            )

        val terminal = waitForTerminalStatus(actor, submitted.executionId)
        assertThat(terminal.status).isEqualTo("SUCCEEDED")

        val firstPage =
            queryExecutionManager.getQueryResults(
                actor = actor,
                isSystemAdmin = false,
                executionId = submitted.executionId,
                request = QueryResultsRequest(pageSize = 10),
            )
        assertThat(firstPage.rows).hasSize(10)
        assertThat(firstPage.rows[0][0]).isEqualTo("1")
        assertThat(firstPage.nextPageToken).isNotBlank()

        val secondPage =
            queryExecutionManager.getQueryResults(
                actor = actor,
                isSystemAdmin = false,
                executionId = submitted.executionId,
                request = QueryResultsRequest(pageToken = firstPage.nextPageToken, pageSize = 10),
            )
        assertThat(secondPage.rows).hasSize(10)
        assertThat(secondPage.rows[0][0]).isEqualTo("11")
    }

    @Test
    fun `postgres execution can be canceled while blocked`() {
        val datasourceId = registerPostgresDatasource()
        val actor = "tc-postgres-cancel-user"
        val advisoryLockKey = 424_242L

        DriverManager
            .getConnection(
                postgresContainer.jdbcUrl,
                postgresContainer.username,
                postgresContainer.password,
            ).use { lockConnection ->
                lockConnection.createStatement().use { lockStatement ->
                    lockStatement.execute("select pg_advisory_lock($advisoryLockKey)")

                    try {
                        val submitted =
                            queryExecutionManager.submitQuery(
                                actor = actor,
                                ipAddress = "127.0.0.1",
                                request =
                                    QueryExecutionRequest(
                                        datasourceId = datasourceId,
                                        sql = "select pg_advisory_lock($advisoryLockKey)",
                                    ),
                                policy = defaultPolicy(),
                            )

                        val cancelResponse =
                            queryExecutionManager.cancelQuery(
                                actor = actor,
                                isSystemAdmin = false,
                                executionId = submitted.executionId,
                            )
                        assertThat(cancelResponse.status).isIn("QUEUED", "RUNNING", "CANCELED")

                        val terminal = waitForTerminalStatus(actor, submitted.executionId)
                        assertThat(terminal.status).isEqualTo("CANCELED")
                    } finally {
                        lockStatement.execute("select pg_advisory_unlock($advisoryLockKey)")
                    }
                }
            }
    }

    @Test
    fun `mysql execution succeeds against containerized datasource`() {
        val datasourceId = registerMysqlDatasource()
        val actor = "tc-mysql-user"

        val submitted =
            queryExecutionManager.submitQuery(
                actor = actor,
                ipAddress = "127.0.0.1",
                request =
                    QueryExecutionRequest(
                        datasourceId = datasourceId,
                        sql = "show databases",
                    ),
                policy = defaultPolicy(),
            )

        val terminal = waitForTerminalStatus(actor, submitted.executionId)
        assertThat(terminal.status).isEqualTo("SUCCEEDED")

        val results =
            queryExecutionManager.getQueryResults(
                actor = actor,
                isSystemAdmin = false,
                executionId = submitted.executionId,
                request = QueryResultsRequest(pageSize = 25),
            )
        assertThat(results.rows).isNotEmpty
        assertThat(results.columns).isNotEmpty
    }

    private fun registerPostgresDatasource(): String {
        val created =
            datasourceRegistryService.createDatasource(
                CreateDatasourceRequest(
                    name = "Postgres TC",
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

    private fun registerMysqlDatasource(): String {
        val created =
            datasourceRegistryService.createDatasource(
                CreateDatasourceRequest(
                    name = "MySQL TC",
                    engine = DatasourceEngine.MYSQL,
                    host = mysqlContainer.host,
                    port = mysqlContainer.getMappedPort(MySQLContainer.MYSQL_PORT),
                    database = mysqlContainer.databaseName,
                    driverId = "mysql-default",
                ),
            )
        datasourceRegistryService.upsertCredentialProfile(
            datasourceId = created.id,
            profileId = "admin-ro",
            request =
                UpsertCredentialProfileRequest(
                    username = mysqlContainer.username,
                    password = mysqlContainer.password,
                    description = "Testcontainers mysql profile",
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
            if (status.status == "SUCCEEDED" || status.status == "FAILED" || status.status == "CANCELED") {
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

        @JvmStatic
        @Container
        val mysqlContainer = MySQLContainer<Nothing>("mysql:8.4")
    }
}
