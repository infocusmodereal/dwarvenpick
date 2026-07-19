package com.dwarvenpick.app.query

import com.dwarvenpick.app.rbac.QueryAccessPolicy
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@SpringBootTest(
    properties = [
        "dwarvenpick.query.max-persisted-result-bytes=24",
        "dwarvenpick.query.result-chunk-rows=1",
    ],
)
class QueryResultStorageBudgetTests {
    @Autowired
    private lateinit var queryRuntimeRepository: QueryRuntimeRepository

    @Autowired
    private lateinit var queryResultPersistenceRepository: QueryResultPersistenceRepository

    @Autowired
    private lateinit var queryResultStorageMetrics: QueryResultStorageMetrics

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var queryExecutionManager: QueryExecutionManager

    @BeforeEach
    fun resetRuntime() {
        queryRuntimeRepository.clear()
        localExecutions().clear()
    }

    @Test
    fun `concurrent replicas cannot exceed the shared persisted result budget`() {
        val records = listOf(runtimeRecord("storage-budget-a"), runtimeRecord("storage-budget-b"))
        records.forEach(queryRuntimeRepository::save)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val attempts =
                records.map { record ->
                    executor.submit<Boolean> {
                        start.await()
                        try {
                            queryRuntimeRepository.appendResultPage(
                                record.executionId,
                                record.ownerInstanceId,
                                resultPage("1234567890"),
                            )
                            true
                        } catch (_: QueryResultStorageBudgetExceededException) {
                            false
                        }
                    }
                }
            start.countDown()

            assertThat(attempts.map { attempt -> attempt.get() }).containsExactlyInAnyOrder(true, false)
        } finally {
            executor.shutdownNow()
        }

        val snapshot = queryResultPersistenceRepository.storageSnapshot()
        assertThat(snapshot.usedBytes).isEqualTo(16)
        assertThat(records.sumOf { record -> queryRuntimeRepository.countResultPages(record.executionId) }).isEqualTo(1)
        assertThat(records.map { record -> queryRuntimeRepository.findMetadata(record.executionId) }).doesNotContainNull()

        queryResultStorageMetrics.refresh()
        assertThat(meterRegistry.get("dwarvenpick.query.result_storage.used.bytes").gauge().value()).isEqualTo(16.0)
        assertThat(meterRegistry.get("dwarvenpick.query.result_storage.budget.bytes").gauge().value()).isEqualTo(24.0)
        assertThat(meterRegistry.get("dwarvenpick.query.result_storage.headroom.bytes").gauge().value()).isEqualTo(8.0)
        assertThat(meterRegistry.get("dwarvenpick.query.result_storage.rejections").counter().count()).isGreaterThanOrEqualTo(1.0)
    }

    @Test
    fun `storage pressure truncates results without failing query metadata`() {
        val actor = "storage-pressure-user"
        val submitted =
            queryExecutionManager.submitQuery(
                actor = actor,
                ipAddress = "127.0.0.1",
                request =
                    QueryExecutionRequest(
                        datasourceId = "unit-test-datasource",
                        sql = "select generate_series(1,10)",
                    ),
                policy =
                    QueryAccessPolicy(
                        credentialProfile = "read-only",
                        readOnly = true,
                        maxRowsPerQuery = 100,
                        maxRuntimeSeconds = 10,
                        concurrencyLimit = 1,
                    ),
            )

        val terminal = waitForTerminalStatus(actor, submitted.executionId)
        val results =
            queryExecutionManager.getQueryResults(
                actor = actor,
                isSystemAdmin = false,
                executionId = submitted.executionId,
                request = QueryResultsRequest(pageSize = 100),
            )

        assertThat(terminal.status).isEqualTo(QueryExecutionStatus.SUCCEEDED.name)
        assertThat(terminal.rowLimitReached).isTrue()
        assertThat(terminal.message).contains("persisted result storage budget")
        assertThat(results.rows).hasSize(3)
        assertThat(queryRuntimeRepository.findMetadata(submitted.executionId)?.status)
            .isEqualTo(QueryExecutionStatus.SUCCEEDED)
    }

    private fun waitForTerminalStatus(
        actor: String,
        executionId: String,
    ): QueryExecutionStatusResponse {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val status = queryExecutionManager.getExecutionStatus(actor, false, executionId)
            if (status.status in setOf("SUCCEEDED", "FAILED", "CANCELED")) {
                return status
            }
            Thread.sleep(25)
        }
        return queryExecutionManager.getExecutionStatus(actor, false, executionId)
    }

    @Suppress("UNCHECKED_CAST")
    private fun localExecutions(): ConcurrentHashMap<String, Any> {
        val field = QueryExecutionManager::class.java.getDeclaredField("executions")
        field.isAccessible = true
        return field.get(queryExecutionManager) as ConcurrentHashMap<String, Any>
    }

    private fun runtimeRecord(executionId: String): PersistedQueryRuntimeRecord {
        val now = Instant.now()
        return PersistedQueryRuntimeRecord(
            executionId = executionId,
            actor = "storage-budget-user",
            ipAddress = "127.0.0.1",
            datasourceId = "unit-test-datasource",
            credentialProfile = "read-only",
            defaultSchema = null,
            justification = null,
            sql = "select 1",
            sqlRedacted = false,
            queryHash = "hash-$executionId",
            maxRowsPerQuery = 100,
            maxRuntimeSeconds = 30,
            concurrencyLimit = 2,
            scriptStatementCount = 1,
            scriptStopOnError = true,
            scriptTransactionMode = ScriptTransactionMode.AUTOCOMMIT,
            scriptStatements = emptyList(),
            status = QueryExecutionStatus.RUNNING,
            message = "Query is running.",
            errorSummary = null,
            submittedAt = now,
            startedAt = now,
            completedAt = null,
            rowLimitReached = false,
            columns = emptyList(),
            rows = emptyList(),
            lastAccessedAt = now,
            resultsExpired = false,
            cancelRequested = false,
            ownerInstanceId = executionId,
            heartbeatAt = now,
        )
    }

    private fun resultPage(value: String): QueryResultPageSnapshot =
        QueryResultPageSnapshot(
            pageIndex = 0,
            startRow = 0,
            rows = listOf(listOf(value)),
            logicalByteCount = value.length.toLong(),
        )
}
