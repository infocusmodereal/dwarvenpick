package com.dwarvenpick.app.query

import java.time.Duration
import java.time.Instant

data class QueryHistoryRecord(
    val executionId: String,
    val actor: String,
    val datasourceId: String,
    val credentialProfile: String,
    val justification: String?,
    val queryHash: String,
    val queryText: String?,
    val queryTextRedacted: Boolean,
    val status: QueryExecutionStatus,
    val message: String,
    val errorSummary: String?,
    val rowCount: Int,
    val columnCount: Int,
    val rowLimitReached: Boolean,
    val maxRowsPerQuery: Int,
    val maxRuntimeSeconds: Int,
    val submittedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
)

enum class QueryHistorySortOrder {
    NEWEST,
    OLDEST,
}

data class QueryHistoryFilter(
    val actor: String,
    val isSystemAdmin: Boolean,
    val datasourceId: String?,
    val status: QueryExecutionStatus?,
    val from: Instant?,
    val to: Instant?,
    val limit: Int,
    val offset: Int,
    val actorFilter: String?,
    val sortOrder: QueryHistorySortOrder,
)

fun QueryHistoryRecord.toResponse(): QueryHistoryEntryResponse {
    val durationMs =
        if (startedAt != null && completedAt != null) {
            Duration.between(startedAt, completedAt).toMillis().coerceAtLeast(0)
        } else {
            null
        }

    return QueryHistoryEntryResponse(
        executionId = executionId,
        actor = actor,
        datasourceId = datasourceId,
        status = status.name,
        message = message,
        queryHash = queryHash,
        queryText = queryText,
        queryTextRedacted = queryTextRedacted,
        errorSummary = errorSummary,
        rowCount = rowCount,
        columnCount = columnCount,
        rowLimitReached = rowLimitReached,
        maxRowsPerQuery = maxRowsPerQuery,
        maxRuntimeSeconds = maxRuntimeSeconds,
        credentialProfile = credentialProfile,
        justification = justification,
        submittedAt = submittedAt.toString(),
        startedAt = startedAt?.toString(),
        completedAt = completedAt?.toString(),
        durationMs = durationMs,
    )
}
