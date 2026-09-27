package com.dwarvenpick.app.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.time.temporal.ChronoUnit

@SpringBootTest(
    properties = [
        "dwarvenpick.auth.password-policy.min-length=8",
    ],
)
class QueryHistoryRepositoryTests {
    @Autowired
    private lateinit var queryHistoryRepository: QueryHistoryRepository

    @Autowired
    private lateinit var queryRetentionService: QueryRetentionService

    @BeforeEach
    fun resetHistory() {
        queryHistoryRepository.clear()
    }

    @Test
    fun `history records survive beyond in-memory execution cleanup and support offset pagination`() {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        queryHistoryRepository.save(historyRecord("exec-1", submittedAt = now.minusSeconds(30), queryText = "select 1"))
        queryHistoryRepository.save(historyRecord("exec-2", submittedAt = now.minusSeconds(20), queryText = "select 2"))
        queryHistoryRepository.save(historyRecord("exec-3", submittedAt = now.minusSeconds(10), queryText = "select 3"))

        val firstPage =
            queryHistoryRepository.list(
                historyFilter(limit = 2, offset = 0, sortOrder = QueryHistorySortOrder.NEWEST),
            )
        val secondPage =
            queryHistoryRepository.list(
                historyFilter(limit = 2, offset = 2, sortOrder = QueryHistorySortOrder.NEWEST),
            )

        assertThat(firstPage.map { record -> record.executionId }).containsExactly("exec-3", "exec-2")
        assertThat(secondPage.map { record -> record.executionId }).containsExactly("exec-1")
        assertThat(firstPage.first().defaultSchema).isEqualTo("Viper2")
    }

    @Test
    fun `retention service prunes persisted query history`() {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        queryHistoryRepository.save(
            historyRecord("old-exec", submittedAt = now.minus(31, ChronoUnit.DAYS), queryText = "select old"),
        )
        queryHistoryRepository.save(
            historyRecord("new-exec", submittedAt = now.minus(1, ChronoUnit.DAYS), queryText = "select new"),
        )

        queryRetentionService.pruneHistoryAndAudit()

        val remaining =
            queryHistoryRepository.list(
                historyFilter(limit = 10, offset = 0, sortOrder = QueryHistorySortOrder.NEWEST),
            )
        assertThat(remaining.map { record -> record.executionId }).containsExactly("new-exec")
    }

    @Test
    fun `query text redaction updates persisted history without deleting the row`() {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        queryHistoryRepository.save(
            historyRecord("redacted-exec", submittedAt = now.minus(2, ChronoUnit.DAYS), queryText = "select secret"),
        )

        val redacted = queryHistoryRepository.redactQueryTextOlderThan(now.minus(1, ChronoUnit.DAYS))

        val rows =
            queryHistoryRepository.list(
                historyFilter(limit = 10, offset = 0, sortOrder = QueryHistorySortOrder.NEWEST),
            )
        assertThat(redacted).isEqualTo(1)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().queryText).isNull()
        assertThat(rows.single().queryTextRedacted).isTrue()
    }

    @Test
    fun `count uses the same access and filters as history pages without a page limit`() {
        val now = Instant.now()
        queryHistoryRepository.save(historyRecord("a", now, "select 1"))
        queryHistoryRepository.save(historyRecord("b", now.minusSeconds(60), "select 2").copy(actor = "other"))
        queryHistoryRepository.save(historyRecord("c", now, "select 3").copy(status = QueryExecutionStatus.FAILED))
        val filter = historyFilter(1, 10, QueryHistorySortOrder.NEWEST)
        assertThat(queryHistoryRepository.count(filter)).isEqualTo(2)
        assertThat(queryHistoryRepository.count(filter.copy(actorFilter = "other"))).isEqualTo(2)
        assertThat(queryHistoryRepository.count(filter.copy(isSystemAdmin = true))).isEqualTo(3)
        assertThat(queryHistoryRepository.count(filter.copy(isSystemAdmin = true, actorFilter = "other"))).isEqualTo(1)
        assertThat(queryHistoryRepository.count(filter.copy(status = QueryExecutionStatus.SUCCEEDED))).isEqualTo(1)
        assertThat(queryHistoryRepository.count(filter.copy(datasourceId = "unmatched"))).isZero()
        assertThat(queryHistoryRepository.count(filter.copy(isSystemAdmin = true, from = now.minusSeconds(1)))).isEqualTo(2)
        assertThat(queryHistoryRepository.count(filter.copy(to = now.minusSeconds(1)))).isZero()
    }

    private fun historyRecord(
        executionId: String,
        submittedAt: Instant,
        queryText: String,
    ): QueryHistoryRecord =
        QueryHistoryRecord(
            executionId = executionId,
            actor = "analyst",
            datasourceId = "trino-warehouse",
            credentialProfile = "read-only",
            defaultSchema = "Viper2",
            justification = null,
            queryHash = "hash-$executionId",
            queryText = queryText,
            queryTextRedacted = false,
            status = QueryExecutionStatus.SUCCEEDED,
            message = "Query completed successfully.",
            errorSummary = null,
            rowCount = 1,
            columnCount = 1,
            rowLimitReached = false,
            maxRowsPerQuery = 5000,
            maxRuntimeSeconds = 300,
            submittedAt = submittedAt,
            startedAt = submittedAt.plusMillis(10),
            completedAt = submittedAt.plusMillis(20),
        )

    private fun historyFilter(
        limit: Int,
        offset: Int,
        sortOrder: QueryHistorySortOrder,
    ): QueryHistoryFilter =
        QueryHistoryFilter(
            actor = "analyst",
            isSystemAdmin = false,
            datasourceId = null,
            status = null,
            from = null,
            to = null,
            limit = limit,
            offset = offset,
            actorFilter = null,
            sortOrder = sortOrder,
        )
}
