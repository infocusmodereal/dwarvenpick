package com.dwarvenpick.app.query

import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.rbac.QueryAccessPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest(
    properties = [
        "dwarvenpick.query.max-buffered-bytes=100",
        "dwarvenpick.query.max-buffered-bytes-per-instance=10",
        "dwarvenpick.query.max-cell-bytes=20",
    ],
)
class QueryExecutionManagerInstanceBufferBudgetTests {
    @Autowired
    private lateinit var queryExecutionManager: QueryExecutionManager

    @Autowired
    private lateinit var queryRuntimeRepository: QueryRuntimeRepository

    @BeforeEach
    fun resetRuntime() {
        queryRuntimeRepository.clear()
        reflectExecutions(queryExecutionManager).clear()
        reflectInstanceBufferedResultBytes(queryExecutionManager).set(0)
    }

    @Test
    fun `completed results release instance buffer budget for later queries`() {
        val firstActor = "instance-budget-user-one"
        val first =
            queryExecutionManager.submitQuery(
                actor = firstActor,
                ipAddress = "127.0.0.1",
                request =
                    QueryExecutionRequest(
                        datasourceId = "unit-test-datasource",
                        sql = "select repeat('x', 8) as payload",
                    ),
                policy = testPolicy(maxRowsPerQuery = 10),
            )

        val firstResults = waitForQueryResults(firstActor, first.executionId)
        assertThat(firstResults.rows).containsExactly(listOf("xxxxxxxx"))

        val secondActor = "instance-budget-user-two"
        val second =
            queryExecutionManager.submitQuery(
                actor = secondActor,
                ipAddress = "127.0.0.1",
                request =
                    QueryExecutionRequest(
                        datasourceId = "unit-test-datasource",
                        sql = "select repeat('y', 8) as payload",
                    ),
                policy = testPolicy(maxRowsPerQuery = 10),
            )

        val secondTerminal = waitForTerminalStatus(secondActor, second.executionId)
        val secondResults = waitForQueryResults(secondActor, second.executionId)

        assertThat(secondTerminal.status).isEqualTo(QueryExecutionStatus.SUCCEEDED.name)
        assertThat(secondTerminal.message).isEqualTo("Query succeeded.")
        assertThat(secondTerminal.rowLimitReached).isFalse()
        assertThat(secondResults.rowLimitReached).isFalse()
        assertThat(secondResults.rows).containsExactly(listOf("yyyyyyyy"))
        assertThat(reflectInstanceBufferedResultBytes(queryExecutionManager).get()).isZero()
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

    private fun reflectInstanceBufferedResultBytes(manager: QueryExecutionManager): AtomicLong {
        val field = QueryExecutionManager::class.java.getDeclaredField("instanceBufferedResultBytes")
        field.isAccessible = true
        return field.get(manager) as AtomicLong
    }
}
