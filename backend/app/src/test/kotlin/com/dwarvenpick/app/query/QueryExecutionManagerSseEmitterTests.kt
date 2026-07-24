package com.dwarvenpick.app.query

import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.rbac.QueryAccessPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
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

    @Autowired
    private lateinit var queryRuntimeRepository: QueryRuntimeRepository

    @BeforeEach
    fun resetRuntime() {
        queryRuntimeRepository.clear()
        reflectExecutions(queryExecutionManager).clear()
    }

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
                        engine = DatasourceEngine.POSTGRESQL,
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

    @Test
    fun `query status and results survive local execution map loss`() {
        val actor = "runtime-fallback-user"
        val submitted =
            queryExecutionManager.submitQuery(
                actor = actor,
                ipAddress = "127.0.0.1",
                request =
                    QueryExecutionRequest(
                        datasourceId = "unit-test-datasource",
                        sql = "select generate_series(1,5) as one",
                    ),
                policy =
                    QueryAccessPolicy(
                        credentialProfile = "unit-test-profile",
                        engine = DatasourceEngine.POSTGRESQL,
                        readOnly = true,
                        maxRowsPerQuery = 100,
                        maxRuntimeSeconds = 10,
                        concurrencyLimit = 1,
                    ),
            )

        val terminal = waitForTerminalStatus(actor, submitted.executionId)
        assertThat(terminal.status).isEqualTo(QueryExecutionStatus.SUCCEEDED.name)
        val persistedTerminal = waitForPersistedTerminalStatus(submitted.executionId)
        assertThat(persistedTerminal.status).isEqualTo(QueryExecutionStatus.SUCCEEDED)

        reflectExecutions(queryExecutionManager).clear()

        val status =
            queryExecutionManager.getExecutionStatus(
                actor = actor,
                isSystemAdmin = false,
                executionId = submitted.executionId,
            )
        val firstPage = waitForQueryResults(actor = actor, executionId = submitted.executionId, pageSize = 2)
        val secondPage =
            queryExecutionManager.getQueryResults(
                actor = actor,
                isSystemAdmin = false,
                executionId = submitted.executionId,
                request = QueryResultsRequest(pageToken = firstPage.nextPageToken, pageSize = 2),
            )
        val thirdPage =
            queryExecutionManager.getQueryResults(
                actor = actor,
                isSystemAdmin = false,
                executionId = submitted.executionId,
                request = QueryResultsRequest(pageToken = secondPage.nextPageToken, pageSize = 2),
            )
        val export =
            queryExecutionManager.prepareCsvExport(
                actor = actor,
                isSystemAdmin = false,
                executionId = submitted.executionId,
                includeHeaders = true,
                maxExportRows = 10,
            )

        assertThat(status.status).isEqualTo(QueryExecutionStatus.SUCCEEDED.name)
        assertThat(firstPage.rows).containsExactly(listOf("1"), listOf("2"))
        assertThat(secondPage.rows).containsExactly(listOf("3"), listOf("4"))
        assertThat(thirdPage.rows).containsExactly(listOf("5"))
        assertThat(thirdPage.nextPageToken).isNull()
        assertThat(export.rows.toList()).containsExactly(
            listOf("1"),
            listOf("2"),
            listOf("3"),
            listOf("4"),
            listOf("5"),
        )
    }

    private fun waitForPersistedTerminalStatus(
        executionId: String,
        timeoutMs: Long = 5000,
    ): PersistedQueryRuntimeMetadataRecord {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastRecord: PersistedQueryRuntimeMetadataRecord? = null
        while (System.currentTimeMillis() < deadline) {
            val record = queryRuntimeRepository.findMetadata(executionId)
            lastRecord = record
            if (
                record != null &&
                record.status in setOf(QueryExecutionStatus.SUCCEEDED, QueryExecutionStatus.FAILED, QueryExecutionStatus.CANCELED)
            ) {
                return record
            }
            Thread.sleep(25)
        }
        return lastRecord ?: throw AssertionError("Query runtime metadata was not persisted before timeout.")
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

    private fun waitForQueryResults(
        actor: String,
        executionId: String,
        pageSize: Int = 10,
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
                    request = QueryResultsRequest(pageSize = pageSize),
                )
            } catch (error: QueryResultsNotReadyException) {
                lastError = error
                Thread.sleep(25)
            }
        }
        throw lastError ?: QueryResultsNotReadyException("Query results were not ready before timeout.")
    }

    @Suppress("UNCHECKED_CAST")
    private fun reflectSubscribers(manager: QueryExecutionManager): ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> {
        val field = QueryExecutionManager::class.java.getDeclaredField("subscribers")
        field.isAccessible = true
        return field.get(manager) as ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun reflectExecutions(manager: QueryExecutionManager): ConcurrentHashMap<String, Any> {
        val field = QueryExecutionManager::class.java.getDeclaredField("executions")
        field.isAccessible = true
        return field.get(manager) as ConcurrentHashMap<String, Any>
    }
}
