package com.dwarvenpick.app.query

import com.dwarvenpick.app.datasource.ConnectionSpec
import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.datasource.DatasourcePoolManager
import com.dwarvenpick.app.datasource.PoolSettings
import com.dwarvenpick.app.datasource.QueryConnectionHandle
import com.dwarvenpick.app.rbac.QueryAccessPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

@SpringBootTest(
    properties = [
        "dwarvenpick.query.max-buffered-bytes=10",
        "dwarvenpick.query.max-cell-bytes=8",
    ],
)
class QueryExecutionManagerBufferLimitTests {
    @Autowired
    private lateinit var queryExecutionManager: QueryExecutionManager

    @Autowired
    private lateinit var queryRuntimeRepository: QueryRuntimeRepository

    @Autowired
    private lateinit var datasourcePoolManager: DatasourcePoolManager

    @BeforeEach
    fun resetRuntime() {
        queryRuntimeRepository.clear()
        reflectExecutions(queryExecutionManager).clear()
    }

    @Test
    fun `query result buffering stops at byte limit`() {
        val actor = "buffer-limit-user"
        val submitted =
            queryExecutionManager.submitQuery(
                actor = actor,
                ipAddress = "127.0.0.1",
                request =
                    QueryExecutionRequest(
                        datasourceId = "unit-test-datasource",
                        sql = "select generate_series(1,25)",
                    ),
                policy = testPolicy(maxRowsPerQuery = 100),
            )

        val terminal = waitForTerminalStatus(actor, submitted.executionId)
        val results = waitForQueryResults(actor, submitted.executionId)

        assertThat(terminal.status).isEqualTo(QueryExecutionStatus.SUCCEEDED.name)
        assertThat(terminal.message).contains("result buffer size limit")
        assertThat(terminal.rowLimitReached).isTrue()
        assertThat(results.rowLimitReached).isTrue()
        assertThat(results.rows.size).isLessThan(25)
    }

    @Test
    fun `oversized cell values are truncated before buffering`() {
        val actor = "cell-limit-user"
        val submitted =
            queryExecutionManager.submitQuery(
                actor = actor,
                ipAddress = "127.0.0.1",
                request =
                    QueryExecutionRequest(
                        datasourceId = "unit-test-datasource",
                        sql = "select repeat('x', 12) as payload",
                    ),
                policy = testPolicy(maxRowsPerQuery = 10),
            )

        val terminal = waitForTerminalStatus(actor, submitted.executionId)
        val results = waitForQueryResults(actor, submitted.executionId)

        assertThat(terminal.status).isEqualTo(QueryExecutionStatus.SUCCEEDED.name)
        assertThat(terminal.message).contains("cell size limit")
        assertThat(results.rowLimitReached).isTrue()
        assertThat(results.rows).containsExactly(listOf("xxxxx..."))
    }

    @Test
    fun `starrocks mysql connector uses streaming fetch size`() {
        val fetchSizeFor =
            QueryExecutionManager::class.java
                .getDeclaredMethod("jdbcFetchSizeFor", QueryConnectionHandle::class.java)
                .apply { isAccessible = true }

        val starrocksFetchSize =
            fetchSizeFor.invoke(
                queryExecutionManager,
                queryConnectionHandle(
                    engine = DatasourceEngine.STARROCKS,
                    driverClass = "com.mysql.cj.jdbc.Driver",
                ),
            )
        val mysqlFetchSize =
            fetchSizeFor.invoke(
                queryExecutionManager,
                queryConnectionHandle(
                    engine = DatasourceEngine.MYSQL,
                    driverClass = "com.mysql.cj.jdbc.Driver",
                ),
            )
        val mismatchedEngineFetchSize =
            fetchSizeFor.invoke(
                queryExecutionManager,
                queryConnectionHandle(
                    engine = DatasourceEngine.POSTGRESQL,
                    driverClass = "com.mysql.cj.jdbc.Driver",
                ),
            )
        val mismatchedDriverFetchSize =
            fetchSizeFor.invoke(
                queryExecutionManager,
                queryConnectionHandle(
                    engine = DatasourceEngine.MYSQL,
                    driverClass = "org.postgresql.Driver",
                ),
            )
        val postgresFetchSize =
            fetchSizeFor.invoke(
                queryExecutionManager,
                queryConnectionHandle(
                    engine = DatasourceEngine.POSTGRESQL,
                    driverClass = "org.postgresql.Driver",
                ),
            )

        assertThat(starrocksFetchSize).isEqualTo(Int.MIN_VALUE)
        assertThat(mysqlFetchSize).isEqualTo(Int.MIN_VALUE)
        assertThat(mismatchedEngineFetchSize).isEqualTo(500)
        assertThat(mismatchedDriverFetchSize).isEqualTo(500)
        assertThat(postgresFetchSize).isEqualTo(500)
    }

    @Test
    fun `starrocks mysql connector pools discard unread streaming results`() {
        val shouldDiscardUnreadResults =
            DatasourcePoolManager::class.java
                .getDeclaredMethod("shouldDiscardUnreadMysqlStreamingResults", ConnectionSpec::class.java)
                .apply { isAccessible = true }

        val starrocksDiscardUnreadResults =
            shouldDiscardUnreadResults.invoke(
                datasourcePoolManager,
                connectionSpec(
                    engine = DatasourceEngine.STARROCKS,
                    driverClass = "com.mysql.cj.jdbc.Driver",
                ),
            )
        val mysqlDiscardUnreadResults =
            shouldDiscardUnreadResults.invoke(
                datasourcePoolManager,
                connectionSpec(
                    engine = DatasourceEngine.MYSQL,
                    driverClass = "com.mysql.cj.jdbc.Driver",
                ),
            )
        val mismatchedEngineDiscardUnreadResults =
            shouldDiscardUnreadResults.invoke(
                datasourcePoolManager,
                connectionSpec(
                    engine = DatasourceEngine.POSTGRESQL,
                    driverClass = "com.mysql.cj.jdbc.Driver",
                ),
            )
        val mismatchedDriverDiscardUnreadResults =
            shouldDiscardUnreadResults.invoke(
                datasourcePoolManager,
                connectionSpec(
                    engine = DatasourceEngine.MYSQL,
                    driverClass = "org.postgresql.Driver",
                ),
            )
        val postgresDiscardUnreadResults =
            shouldDiscardUnreadResults.invoke(
                datasourcePoolManager,
                connectionSpec(
                    engine = DatasourceEngine.POSTGRESQL,
                    driverClass = "org.postgresql.Driver",
                ),
            )

        assertThat(starrocksDiscardUnreadResults).isEqualTo(true)
        assertThat(mysqlDiscardUnreadResults).isEqualTo(true)
        assertThat(mismatchedEngineDiscardUnreadResults).isEqualTo(false)
        assertThat(mismatchedDriverDiscardUnreadResults).isEqualTo(false)
        assertThat(postgresDiscardUnreadResults).isEqualTo(false)
    }

    private fun testPolicy(maxRowsPerQuery: Int): QueryAccessPolicy =
        QueryAccessPolicy(
            credentialProfile = "unit-test-profile",
            engine = DatasourceEngine.POSTGRESQL,
            readOnly = true,
            maxRowsPerQuery = maxRowsPerQuery,
            maxRuntimeSeconds = 10,
            concurrencyLimit = 1,
        )

    private fun queryConnectionHandle(
        engine: DatasourceEngine,
        driverClass: String,
    ): QueryConnectionHandle =
        QueryConnectionHandle(
            spec = connectionSpec(engine, driverClass),
            connection = mock(Connection::class.java),
        )

    private fun connectionSpec(
        engine: DatasourceEngine,
        driverClass: String,
    ): ConnectionSpec =
        ConnectionSpec(
            datasourceId = "test-datasource",
            datasourceName = "Test datasource",
            credentialProfile = "unit-test-profile",
            engine = engine,
            driverId = "test-driver",
            driverClass = driverClass,
            driverSource = "built-in",
            jdbcUrl = "jdbc:test://localhost",
            username = "test",
            password = "test",
            pool = PoolSettings(),
        )

    private fun waitForTerminalStatus(
        actor: String,
        executionId: String,
        timeoutMs: Long = 5000,
    ): QueryExecutionStatusResponse {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status =
                queryExecutionManager.getExecutionStatus(
                    actor = actor,
                    isSystemAdmin = false,
                    executionId = executionId,
                )
            if (status.status in setOf("SUCCEEDED", "FAILED", "CANCELED")) {
                return status
            }
            Thread.sleep(25)
        }
        return queryExecutionManager.getExecutionStatus(
            actor = actor,
            isSystemAdmin = false,
            executionId = executionId,
        )
    }

    private fun waitForQueryResults(
        actor: String,
        executionId: String,
        timeoutMs: Long = 5000,
    ): QueryResultsResponse {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: QueryResultsNotReadyException? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                return queryExecutionManager.getQueryResults(
                    actor = actor,
                    isSystemAdmin = false,
                    executionId = executionId,
                    request = QueryResultsRequest(pageSize = 100),
                )
            } catch (error: QueryResultsNotReadyException) {
                lastError = error
                Thread.sleep(25)
            }
        }
        throw lastError ?: QueryResultsNotReadyException("Query results were not ready before timeout.")
    }

    @Suppress("UNCHECKED_CAST")
    private fun reflectExecutions(manager: QueryExecutionManager): ConcurrentHashMap<String, Any> {
        val field = QueryExecutionManager::class.java.getDeclaredField("executions")
        field.isAccessible = true
        return field.get(manager) as ConcurrentHashMap<String, Any>
    }
}
