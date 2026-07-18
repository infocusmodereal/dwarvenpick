package com.dwarvenpick.app.query

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

data class QueryRuntimeExecutionUpdate(
    val executionId: String,
    val ownerInstanceId: String,
    val status: QueryExecutionStatus,
    val message: String,
    val errorSummary: String?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val rowCount: Int,
    val columnCount: Int,
    val rowLimitReached: Boolean,
    val columns: List<QueryResultColumn>,
    val scriptStatements: List<QueryScriptStatementSummary>,
    val lastAccessedAt: Instant,
    val cancelRequested: Boolean,
    val heartbeatAt: Instant,
)

class QueryRuntimeWriteRejectedException(
    message: String,
) : RuntimeException(message)

class QueryResultIntegrityException(
    message: String,
) : RuntimeException(message)

private data class LockedExecutionState(
    val status: QueryExecutionStatus,
    val cancelRequested: Boolean,
    val ownerInstanceId: String,
)

private data class LastResultPagePosition(
    val pageIndex: Int,
    val endRow: Int,
)

@Repository
class QueryResultPersistenceRepository(
    jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val jdbc = NamedParameterJdbcTemplate(jdbcTemplate)

    @Transactional
    fun updateExecution(update: QueryRuntimeExecutionUpdate) {
        lockWritableExecution(update.executionId, update.ownerInstanceId)
        updateExecutionMetadata(update)
    }

    @Transactional
    fun appendResultPage(
        executionId: String,
        ownerInstanceId: String,
        page: QueryResultPageSnapshot,
    ) {
        lockRunningExecution(executionId, ownerInstanceId)
        validateNextPage(executionId, page)
        insertResultPage(executionId, page)
    }

    @Transactional
    fun finalizeSucceededExecution(
        update: QueryRuntimeExecutionUpdate,
        finalPage: QueryResultPageSnapshot?,
    ) {
        require(update.status == QueryExecutionStatus.SUCCEEDED) { "Success finalization requires SUCCEEDED status." }
        lockRunningExecution(update.executionId, update.ownerInstanceId)
        if (finalPage != null) {
            validateNextPage(update.executionId, finalPage)
            insertResultPage(update.executionId, finalPage)
        }
        val persistedRowCount = resultPageRowCount(update.executionId)
        if (persistedRowCount != update.rowCount) {
            throw QueryResultIntegrityException(
                "Persisted result row count $persistedRowCount did not match finalized row count ${update.rowCount}.",
            )
        }
        updateExecutionMetadata(update)
    }

    @Transactional
    fun finalizeWithoutResults(update: QueryRuntimeExecutionUpdate) {
        val current = lockExecution(update.executionId)
        if (current.status in terminalStatuses && current.status == update.status) {
            deleteResultPages(update.executionId)
            return
        }
        if (current.status !in activeStatuses || current.ownerInstanceId != update.ownerInstanceId) {
            throw QueryRuntimeWriteRejectedException("Execution '${update.executionId}' is no longer writable by this backend.")
        }
        deleteResultPages(update.executionId)
        updateExecutionMetadata(update.copy(rowCount = 0, columnCount = 0, columns = emptyList()))
    }

    @Transactional
    fun beginResultAccess(executionId: String): Boolean {
        val accessedAt = Instant.now()
        val current = runCatching { lockExecution(executionId) }.getOrNull() ?: return false
        if (current.status != QueryExecutionStatus.SUCCEEDED || current.cancelRequested) {
            return false
        }
        return jdbc.update(
            """
            UPDATE query_runtime_executions
            SET last_accessed_at = :accessedAt
            WHERE execution_id = :executionId
              AND status = 'SUCCEEDED'
              AND results_expired = FALSE
            """.trimIndent(),
            mapOf("executionId" to executionId, "accessedAt" to accessedAt.toTimestamp()),
        ) == 1
    }

    fun touchResultAccess(
        executionId: String,
        accessedAt: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE query_runtime_executions
            SET last_accessed_at = :accessedAt
            WHERE execution_id = :executionId
              AND status = 'SUCCEEDED'
              AND results_expired = FALSE
            """.trimIndent(),
            mapOf("executionId" to executionId, "accessedAt" to accessedAt.toTimestamp()),
        ) == 1

    @Transactional
    fun expireResultsIfIdle(
        executionId: String,
        cutoff: Instant,
        message: String,
    ): Boolean {
        val current = runCatching { lockExecution(executionId) }.getOrNull() ?: return false
        if (current.status != QueryExecutionStatus.SUCCEEDED) {
            return false
        }
        val expired =
            jdbc.update(
                """
                UPDATE query_runtime_executions
                SET rows_json = '[]',
                    columns_json = '[]',
                    row_count = 0,
                    column_count = 0,
                    results_expired = TRUE,
                    message = :message
                WHERE execution_id = :executionId
                  AND results_expired = FALSE
                  AND last_accessed_at < :cutoff
                """.trimIndent(),
                mapOf(
                    "executionId" to executionId,
                    "cutoff" to cutoff.toTimestamp(),
                    "message" to message,
                ),
            ) == 1
        if (expired) {
            deleteResultPages(executionId)
        }
        return expired
    }

    @Transactional
    fun markStaleActiveExecutions(
        cutoff: Instant,
        message: String,
    ): Int {
        val executionIds =
            jdbc.queryForList(
                """
                SELECT execution_id
                FROM query_runtime_executions
                WHERE status IN ('QUEUED', 'RUNNING')
                  AND heartbeat_at < :cutoff
                FOR UPDATE SKIP LOCKED
                """.trimIndent(),
                mapOf("cutoff" to cutoff.toTimestamp()),
                String::class.java,
            )
        executionIds.forEach { executionId ->
            jdbc.update(
                """
                UPDATE query_runtime_executions
                SET status = 'CANCELED',
                    completed_at = :completedAt,
                    cancel_requested = TRUE,
                    message = :message,
                    rows_json = '[]',
                    columns_json = '[]',
                    row_count = 0,
                    column_count = 0
                WHERE execution_id = :executionId
                """.trimIndent(),
                mapOf(
                    "executionId" to executionId,
                    "completedAt" to Instant.now().toTimestamp(),
                    "message" to message,
                ),
            )
            deleteResultPages(executionId)
        }
        return executionIds.size
    }

    private fun lockWritableExecution(
        executionId: String,
        ownerInstanceId: String,
    ) {
        val current = lockExecution(executionId)
        if (current.status !in activeStatuses || current.cancelRequested || current.ownerInstanceId != ownerInstanceId) {
            throw QueryRuntimeWriteRejectedException("Execution '$executionId' is no longer writable by this backend.")
        }
    }

    private fun lockRunningExecution(
        executionId: String,
        ownerInstanceId: String,
    ) {
        val current = lockExecution(executionId)
        if (
            current.status != QueryExecutionStatus.RUNNING ||
            current.cancelRequested ||
            current.ownerInstanceId != ownerInstanceId
        ) {
            throw QueryRuntimeWriteRejectedException("Execution '$executionId' is no longer running on this backend.")
        }
    }

    private fun lockExecution(executionId: String): LockedExecutionState =
        jdbc.queryForObject(
            """
            SELECT status, cancel_requested, owner_instance_id
            FROM query_runtime_executions
            WHERE execution_id = :executionId
            FOR UPDATE
            """.trimIndent(),
            mapOf("executionId" to executionId),
        ) { resultSet, _ ->
            LockedExecutionState(
                status = QueryExecutionStatus.valueOf(resultSet.getString("status")),
                cancelRequested = resultSet.getBoolean("cancel_requested"),
                ownerInstanceId = resultSet.getString("owner_instance_id"),
            )
        } ?: throw QueryRuntimeWriteRejectedException("Execution '$executionId' was not found.")

    private fun validateNextPage(
        executionId: String,
        page: QueryResultPageSnapshot,
    ) {
        val lastPage =
            jdbc
                .query(
                    """
                    SELECT page_index, start_row + row_count AS end_row
                    FROM query_runtime_result_pages
                    WHERE execution_id = :executionId
                    ORDER BY page_index DESC
                    LIMIT 1
                    """.trimIndent(),
                    mapOf("executionId" to executionId),
                ) { resultSet, _ ->
                    LastResultPagePosition(
                        pageIndex = resultSet.getInt("page_index"),
                        endRow = resultSet.getInt("end_row"),
                    )
                }.firstOrNull()
        val expectedPageIndex = lastPage?.pageIndex?.plus(1) ?: 0
        val expectedStartRow = lastPage?.endRow ?: 0
        if (page.pageIndex != expectedPageIndex || page.startRow != expectedStartRow || page.rowCount <= 0) {
            throw QueryRuntimeWriteRejectedException(
                "Result page sequence mismatch for '$executionId': expected page $expectedPageIndex at row $expectedStartRow.",
            )
        }
    }

    private fun insertResultPage(
        executionId: String,
        page: QueryResultPageSnapshot,
    ) {
        val rowsJson = objectMapper.writeValueAsString(page.rows)
        jdbc.update(
            """
            INSERT INTO query_runtime_result_pages (
              execution_id, page_index, start_row, row_count, rows_json, byte_count, created_at
            ) VALUES (
              :executionId, :pageIndex, :startRow, :rowCount, :rowsJson, :byteCount, :createdAt
            )
            """.trimIndent(),
            mapOf(
                "executionId" to executionId,
                "pageIndex" to page.pageIndex,
                "startRow" to page.startRow,
                "rowCount" to page.rowCount,
                "rowsJson" to rowsJson,
                "byteCount" to rowsJson.toByteArray(Charsets.UTF_8).size.toLong(),
                "createdAt" to Instant.now().toTimestamp(),
            ),
        )
    }

    private fun resultPageRowCount(executionId: String): Int =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(row_count), 0) FROM query_runtime_result_pages WHERE execution_id = :executionId",
            mapOf("executionId" to executionId),
            Int::class.java,
        ) ?: 0

    private fun deleteResultPages(executionId: String) {
        jdbc.update(
            "DELETE FROM query_runtime_result_pages WHERE execution_id = :executionId",
            mapOf("executionId" to executionId),
        )
    }

    private fun updateExecutionMetadata(update: QueryRuntimeExecutionUpdate) {
        val updated =
            jdbc.update(
                """
                UPDATE query_runtime_executions
                SET status = :status,
                    message = :message,
                    error_summary = :errorSummary,
                    row_count = :rowCount,
                    column_count = :columnCount,
                    row_limit_reached = :rowLimitReached,
                    script_statements_json = :scriptStatementsJson,
                    columns_json = :columnsJson,
                    rows_json = '[]',
                    started_at = :startedAt,
                    completed_at = :completedAt,
                    last_accessed_at = :lastAccessedAt,
                    cancel_requested = cancel_requested OR :cancelRequested,
                    heartbeat_at = :heartbeatAt
                WHERE execution_id = :executionId
                  AND owner_instance_id = :ownerInstanceId
                """.trimIndent(),
                mapOf(
                    "executionId" to update.executionId,
                    "ownerInstanceId" to update.ownerInstanceId,
                    "status" to update.status.name,
                    "message" to update.message,
                    "errorSummary" to update.errorSummary,
                    "rowCount" to update.rowCount,
                    "columnCount" to update.columnCount,
                    "rowLimitReached" to update.rowLimitReached,
                    "scriptStatementsJson" to objectMapper.writeValueAsString(update.scriptStatements),
                    "columnsJson" to objectMapper.writeValueAsString(update.columns),
                    "startedAt" to update.startedAt?.toTimestamp(),
                    "completedAt" to update.completedAt?.toTimestamp(),
                    "lastAccessedAt" to update.lastAccessedAt.toTimestamp(),
                    "cancelRequested" to update.cancelRequested,
                    "heartbeatAt" to update.heartbeatAt.toTimestamp(),
                ),
            )
        if (updated != 1) {
            throw QueryRuntimeWriteRejectedException("Execution '${update.executionId}' metadata update was rejected.")
        }
    }

    private fun Instant.toTimestamp(): Timestamp = Timestamp.from(this)

    private companion object {
        val activeStatuses = setOf(QueryExecutionStatus.QUEUED, QueryExecutionStatus.RUNNING)
        val terminalStatuses =
            setOf(QueryExecutionStatus.SUCCEEDED, QueryExecutionStatus.FAILED, QueryExecutionStatus.CANCELED)
    }
}
