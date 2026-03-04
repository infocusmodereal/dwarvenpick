package com.dwarvenpick.app.query

import com.dwarvenpick.app.rbac.QueryAccessPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@SpringBootTest(
    properties = [
        "dwarvenpick.query.sse-heartbeat-interval-ms=3600000",
    ],
)
class QueryExecutionManagerSseEmitterTests {
    @Autowired
    private lateinit var queryExecutionManager: QueryExecutionManager

    @Test
    fun `query execution does not fail when SSE cleanup throws`() {
        val actor = "sse-test-user"
        val subscribers = reflectSubscribers(queryExecutionManager)

        val failingEmitter =
            object : SseEmitter(0L) {
                override fun send(builder: SseEmitter.SseEventBuilder) = throw IllegalStateException("send failed")

                override fun complete() = throw IllegalStateException("complete failed")
            }

        subscribers[actor] = CopyOnWriteArrayList(listOf(failingEmitter))

        val submitted =
            queryExecutionManager.submitQuery(
                actor = actor,
                ipAddress = "127.0.0.1",
                request =
                    QueryExecutionRequest(
                        datasourceId = "unit-test-datasource",
                        sql = "select 1",
                    ),
                policy =
                    QueryAccessPolicy(
                        credentialProfile = "unit-test-profile",
                        readOnly = true,
                        maxRowsPerQuery = 100,
                        maxRuntimeSeconds = 10,
                        concurrencyLimit = 1,
                    ),
            )

        val status = waitForTerminalStatus(actor, submitted.executionId)
        assertThat(status.status).isEqualTo(QueryExecutionStatus.SUCCEEDED.name)

        assertThat(subscribers[actor]).doesNotContain(failingEmitter)
    }

    private fun waitForTerminalStatus(
        actor: String,
        executionId: String,
        timeoutMs: Long = 2000,
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

    @Suppress("UNCHECKED_CAST")
    private fun reflectSubscribers(manager: QueryExecutionManager): ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> {
        val field = QueryExecutionManager::class.java.getDeclaredField("subscribers")
        field.isAccessible = true
        return field.get(manager) as ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>
    }
}
