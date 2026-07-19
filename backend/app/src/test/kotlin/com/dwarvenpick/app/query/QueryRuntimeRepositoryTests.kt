package com.dwarvenpick.app.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@SpringBootTest(
    properties = [
        "dwarvenpick.query.result-chunk-rows=2",
    ],
)
class QueryRuntimeRepositoryTests {
    @Autowired
    private lateinit var queryRuntimeRepository: QueryRuntimeRepository

    @Autowired
    private lateinit var namedParameterJdbcTemplate: NamedParameterJdbcTemplate

    @Autowired
    private lateinit var queryAdmissionRepository: QueryAdmissionRepository

    @BeforeEach
    fun resetRuntime() {
        queryRuntimeRepository.clear()
    }

    @Test
    fun `metadata paths do not deserialize persisted result rows`() {
        val record = runtimeRecord(executionId = "metadata-only-exec")
        queryRuntimeRepository.save(record)
        namedParameterJdbcTemplate.update(
            """
            UPDATE query_runtime_executions
            SET rows_json = :rowsJson,
                cancel_requested = TRUE
            WHERE execution_id = :executionId
            """.trimIndent(),
            mapOf(
                "executionId" to record.executionId,
                "rowsJson" to "not-json",
            ),
        )

        val metadata = queryRuntimeRepository.findMetadata(record.executionId)
        val activeMetadata =
            queryRuntimeRepository.listActiveMetadata(
                actor = record.actor,
                isSystemAdmin = false,
                datasourceId = null,
                actorFilter = null,
            )

        assertThat(metadata?.executionId).isEqualTo(record.executionId)
        assertThat(metadata?.rowCount).isEqualTo(1)
        assertThat(metadata?.columnCount).isEqualTo(1)
        assertThat(metadata?.cancelRequested).isTrue()
        assertThat(activeMetadata).extracting<String> { it.executionId }.containsExactly(record.executionId)

        queryRuntimeRepository.save(record.copy(message = "Still running.", cancelRequested = false))

        val savedMetadata = queryRuntimeRepository.findMetadata(record.executionId)
        val savedFullRecord = queryRuntimeRepository.find(record.executionId)
        assertThat(savedMetadata?.cancelRequested).isTrue()
        assertThat(savedMetadata?.defaultSchema).isEqualTo("analytics")
        assertThat(savedFullRecord?.defaultSchema).isEqualTo("analytics")
        assertThat(savedFullRecord?.rows).containsExactly(listOf("value"))
    }

    @Test
    fun `result rows are persisted and fetched in bounded pages`() {
        val rows = (1..5).map { index -> listOf("value-$index") }
        val record =
            runtimeRecord(executionId = "paged-exec")
                .copy(
                    status = QueryExecutionStatus.SUCCEEDED,
                    completedAt = Instant.now(),
                    rows = rows,
                )

        queryRuntimeRepository.save(record)

        val storedRowsJson =
            namedParameterJdbcTemplate.queryForObject(
                "SELECT rows_json FROM query_runtime_executions WHERE execution_id = :executionId",
                mapOf("executionId" to record.executionId),
                String::class.java,
            )
        assertThat(storedRowsJson).isEqualTo("[]")
        assertThat(queryRuntimeRepository.countResultPages(record.executionId)).isEqualTo(3)
        assertThat(queryRuntimeRepository.findMetadata(record.executionId)?.rowCount).isEqualTo(5)

        namedParameterJdbcTemplate.update(
            """
            UPDATE query_runtime_result_pages
            SET rows_json = :rowsJson
            WHERE execution_id = :executionId
              AND page_index = 2
            """.trimIndent(),
            mapOf("executionId" to record.executionId, "rowsJson" to "not-json"),
        )

        assertThat(queryRuntimeRepository.fetchRows(record.executionId, startOffset = 1, limit = 2))
            .containsExactly(listOf("value-2"), listOf("value-3"))
        assertThat(queryRuntimeRepository.findMetadata(record.executionId)?.rowCount).isEqualTo(5)
    }

    @Test
    fun `expiring results clears result pages`() {
        val record =
            runtimeRecord(executionId = "expire-pages-exec")
                .copy(rows = (1..3).map { index -> listOf("value-$index") })
        queryRuntimeRepository.save(record)

        queryRuntimeRepository.expireResults(record.executionId, "expired")

        assertThat(queryRuntimeRepository.countResultPages(record.executionId)).isZero()
        val metadata = queryRuntimeRepository.findMetadata(record.executionId)
        assertThat(metadata?.resultsExpired).isTrue()
        assertThat(metadata?.rowCount).isZero()
        assertThat(metadata?.columnCount).isZero()
        assertThat(metadata?.columns).isEmpty()
    }

    @Test
    fun `incremental pages finalize without rewriting the complete result`() {
        val record = runtimeRecord("incremental-exec").copy(rows = emptyList(), columns = emptyList())
        queryRuntimeRepository.save(record)
        val firstPage = resultPage(pageIndex = 0, startRow = 0, "one", "two")
        val finalPage = resultPage(pageIndex = 1, startRow = 2, "three")

        queryRuntimeRepository.appendResultPage(record.executionId, record.ownerInstanceId, firstPage)
        assertThat(queryRuntimeRepository.findMetadata(record.executionId)?.status).isEqualTo(QueryExecutionStatus.RUNNING)
        assertThat(queryRuntimeRepository.fetchRows(record.executionId, 0, 10))
            .containsExactly(listOf("one"), listOf("two"))

        queryRuntimeRepository.finalizeSucceededExecution(
            update = runtimeUpdate(record, QueryExecutionStatus.SUCCEEDED, rowCount = 3),
            finalPage = finalPage,
        )

        val metadata = requireNotNull(queryRuntimeRepository.findMetadata(record.executionId))
        assertThat(metadata.status).isEqualTo(QueryExecutionStatus.SUCCEEDED)
        assertThat(metadata.rowCount).isEqualTo(3)
        assertThat(queryRuntimeRepository.countResultPages(record.executionId)).isEqualTo(2)
        assertThat(queryRuntimeRepository.fetchRows(record.executionId, 0, 10))
            .containsExactly(listOf("one"), listOf("two"), listOf("three"))
        assertThat(
            namedParameterJdbcTemplate.queryForObject(
                "SELECT rows_json FROM query_runtime_executions WHERE execution_id = :executionId",
                mapOf("executionId" to record.executionId),
                String::class.java,
            ),
        ).isEqualTo("[]")
    }

    @Test
    fun `incremental page sequence rejects duplicates and gaps`() {
        val record = runtimeRecord("sequence-exec").copy(rows = emptyList(), columns = emptyList())
        queryRuntimeRepository.save(record)
        val firstPage = resultPage(pageIndex = 0, startRow = 0, "one", "two")
        queryRuntimeRepository.appendResultPage(record.executionId, record.ownerInstanceId, firstPage)

        assertThrows<QueryRuntimeWriteRejectedException> {
            queryRuntimeRepository.appendResultPage(record.executionId, record.ownerInstanceId, firstPage)
        }
        assertThrows<QueryRuntimeWriteRejectedException> {
            queryRuntimeRepository.appendResultPage(
                record.executionId,
                record.ownerInstanceId,
                resultPage(pageIndex = 2, startRow = 2, "three"),
            )
        }
        assertThat(queryRuntimeRepository.countResultPages(record.executionId)).isEqualTo(1)
    }

    @Test
    fun `finalization rejects persisted row count divergence as integrity failure`() {
        val record = runtimeRecord("integrity-exec").copy(rows = emptyList(), columns = emptyList())
        queryRuntimeRepository.save(record)
        queryRuntimeRepository.appendResultPage(
            record.executionId,
            record.ownerInstanceId,
            resultPage(0, 0, "one", "two"),
        )

        assertThrows<QueryResultIntegrityException> {
            queryRuntimeRepository.finalizeSucceededExecution(
                runtimeUpdate(record, QueryExecutionStatus.SUCCEEDED, rowCount = 4),
                resultPage(1, 2, "three"),
            )
        }
        assertThat(queryRuntimeRepository.findMetadata(record.executionId)?.status).isEqualTo(QueryExecutionStatus.RUNNING)
        assertThat(queryRuntimeRepository.countResultPages(record.executionId)).isEqualTo(1)
    }

    @Test
    fun `cancel and stale recovery delete partial pages and fence later appends`() {
        val canceled = runtimeRecord("cancel-partial-exec").copy(rows = emptyList(), columns = emptyList())
        queryRuntimeRepository.save(canceled)
        queryRuntimeRepository.appendResultPage(
            canceled.executionId,
            canceled.ownerInstanceId,
            resultPage(0, 0, "one", "two"),
        )
        queryRuntimeRepository.finalizeWithoutResults(runtimeUpdate(canceled, QueryExecutionStatus.CANCELED, rowCount = 0))
        assertThat(queryRuntimeRepository.countResultPages(canceled.executionId)).isZero()
        assertThrows<QueryRuntimeWriteRejectedException> {
            queryRuntimeRepository.appendResultPage(
                canceled.executionId,
                canceled.ownerInstanceId,
                resultPage(0, 0, "late"),
            )
        }

        val stale =
            runtimeRecord("stale-partial-exec")
                .copy(rows = emptyList(), columns = emptyList(), heartbeatAt = Instant.now().minusSeconds(600))
        queryRuntimeRepository.save(stale)
        queryRuntimeRepository.appendResultPage(
            stale.executionId,
            stale.ownerInstanceId,
            resultPage(0, 0, "one"),
        )
        assertThat(queryRuntimeRepository.markStaleActiveExecutions(Instant.now().minusSeconds(60), "stale")).isEqualTo(1)
        assertThat(queryRuntimeRepository.countResultPages(stale.executionId)).isZero()
        assertThat(queryRuntimeRepository.findMetadata(stale.executionId)?.status).isEqualTo(QueryExecutionStatus.CANCELED)
    }

    @Test
    fun `durable result access wins against idle expiration and later expires cleanly`() {
        val record = runtimeRecord("result-access-exec").copy(rows = emptyList(), columns = emptyList())
        queryRuntimeRepository.save(record)
        queryRuntimeRepository.finalizeSucceededExecution(
            runtimeUpdate(record, QueryExecutionStatus.SUCCEEDED, rowCount = 1),
            resultPage(0, 0, "one"),
        )
        queryRuntimeRepository.updateLastAccessed(record.executionId, Instant.now().minusSeconds(600))

        assertThat(queryRuntimeRepository.beginResultAccess(record.executionId)).isTrue()
        assertThat(
            queryRuntimeRepository.expireResultsIfIdle(
                record.executionId,
                Instant.now().minusSeconds(60),
                "expired",
            ),
        ).isFalse()
        assertThat(queryRuntimeRepository.fetchRows(record.executionId, 0, 10)).containsExactly(listOf("one"))

        queryRuntimeRepository.updateLastAccessed(record.executionId, Instant.now().minusSeconds(600))
        assertThat(
            queryRuntimeRepository.expireResultsIfIdle(
                record.executionId,
                Instant.now().minusSeconds(60),
                "expired",
            ),
        ).isTrue()
        assertThat(queryRuntimeRepository.countResultPages(record.executionId)).isZero()
        assertThat(queryRuntimeRepository.beginResultAccess(record.executionId)).isFalse()
    }

    @Test
    fun `local admission serializes count insert and commit`() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results =
                listOf("local-admission-1", "local-admission-2")
                    .map { executionId ->
                        executor.submit<QueryAdmissionResult> {
                            start.await()
                            queryAdmissionRepository.reserve(
                                runtimeRecord(executionId)
                                    .copy(
                                        actor = "local-admission-user",
                                        status = QueryExecutionStatus.QUEUED,
                                        startedAt = null,
                                        columns = emptyList(),
                                        rows = emptyList(),
                                    ),
                                limits = QueryAdmissionLimits(actor = 1, connection = 10, global = 10),
                            )
                        }
                    }
            start.countDown()

            assertThat(results.map { it.get() })
                .containsExactlyInAnyOrder(QueryAdmissionResult.ADMITTED, QueryAdmissionResult.ACTOR_LIMIT_REACHED)
            assertThat(
                queryRuntimeRepository.listActiveMetadata(
                    actor = "local-admission-user",
                    isSystemAdmin = false,
                    datasourceId = null,
                    actorFilter = null,
                ),
            ).hasSize(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `admission lock keys are stable`() {
        assertThat(QueryAdmissionLockKey.forActor("admission-user"))
            .isEqualTo(QueryAdmissionLockKey.forActor("admission-user"))
            .isNotEqualTo(QueryAdmissionLockKey.forActor("other-user"))
        assertThat(QueryAdmissionLockKey.NAMESPACE).isEqualTo(0x44575051)
        assertThat(QueryAdmissionLockKey.GLOBAL).isEqualTo(0x474C4F42)
        assertThat(queryAdmissionRepository.metadataDialect()).isEqualTo("LOCAL")
    }

    private fun runtimeRecord(executionId: String): PersistedQueryRuntimeRecord {
        val submittedAt = Instant.now()
        return PersistedQueryRuntimeRecord(
            executionId = executionId,
            actor = "metadata-user",
            ipAddress = "127.0.0.1",
            datasourceId = "unit-test-datasource",
            credentialProfile = "read-only",
            defaultSchema = "analytics",
            justification = null,
            sql = "select 'value'",
            sqlRedacted = false,
            queryHash = "hash-$executionId",
            maxRowsPerQuery = 5000,
            maxRuntimeSeconds = 300,
            concurrencyLimit = 1,
            scriptStatementCount = 1,
            scriptStopOnError = true,
            scriptTransactionMode = ScriptTransactionMode.AUTOCOMMIT,
            scriptStatements = emptyList(),
            status = QueryExecutionStatus.RUNNING,
            message = "Query is running.",
            errorSummary = null,
            submittedAt = submittedAt,
            startedAt = submittedAt.plusMillis(10),
            completedAt = null,
            rowLimitReached = false,
            columns = listOf(QueryResultColumn(name = "result", jdbcType = "VARCHAR")),
            rows = listOf(listOf("value")),
            lastAccessedAt = submittedAt,
            resultsExpired = false,
            cancelRequested = false,
            ownerInstanceId = "test-instance",
            heartbeatAt = submittedAt,
        )
    }

    private fun resultPage(
        pageIndex: Int,
        startRow: Int,
        vararg values: String,
    ): QueryResultPageSnapshot =
        QueryResultPageSnapshot(
            pageIndex = pageIndex,
            startRow = startRow,
            rows = values.map { value -> listOf(value) },
            logicalByteCount = values.sumOf { value -> value.length }.toLong(),
        )

    private fun runtimeUpdate(
        record: PersistedQueryRuntimeRecord,
        status: QueryExecutionStatus,
        rowCount: Int,
    ): QueryRuntimeExecutionUpdate =
        QueryRuntimeExecutionUpdate(
            executionId = record.executionId,
            ownerInstanceId = record.ownerInstanceId,
            status = status,
            message = status.name,
            errorSummary = null,
            startedAt = record.startedAt,
            completedAt = Instant.now(),
            rowCount = rowCount,
            columnCount = if (rowCount > 0) 1 else 0,
            rowLimitReached = false,
            columns = if (rowCount > 0) listOf(QueryResultColumn("result", "VARCHAR")) else emptyList(),
            scriptStatements = emptyList(),
            lastAccessedAt = Instant.now(),
            cancelRequested = status == QueryExecutionStatus.CANCELED,
            heartbeatAt = Instant.now(),
        )
}
