package com.dwarvenpick.app.query

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

enum class QueryExecutionStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED,
}

data class QueryExecutionRequest(
    @field:NotBlank(message = "datasourceId is required.")
    val datasourceId: String = "",
    @field:NotBlank(message = "sql is required.")
    val sql: String = "",
    /**
     * Optional credential profile override.
     *
     * When omitted, the backend resolves the effective credential profile via RBAC rules (or SYSTEM_ADMIN defaults).
     */
    val credentialProfile: String? = null,
)

data class QueryExecutionResponse(
    val executionId: String,
    val datasourceId: String,
    val status: String,
    val message: String,
    val queryHash: String,
)

data class QueryCancelResponse(
    val executionId: String,
    val status: String,
    val message: String,
    val canceledAt: String,
)

data class QueryExecutionStatusResponse(
    val executionId: String,
    val datasourceId: String,
    val status: String,
    val message: String,
    val submittedAt: String,
    val startedAt: String?,
    val completedAt: String?,
    val queryHash: String,
    val errorSummary: String?,
    val rowCount: Int,
    val columnCount: Int,
    val rowLimitReached: Boolean,
    val maxRowsPerQuery: Int,
    val maxRuntimeSeconds: Int,
    val credentialProfile: String,
)

data class QueryResultsRequest(
    val pageToken: String? = null,
    @field:Min(1, message = "pageSize must be between 1 and 1000.")
    @field:Max(1000, message = "pageSize must be between 1 and 1000.")
    val pageSize: Int? = null,
)

data class QueryResultColumn(
    val name: String,
    val jdbcType: String,
)

data class QueryResultsResponse(
    val executionId: String,
    val status: String,
    val columns: List<QueryResultColumn>,
    val rows: List<List<String?>>,
    val pageSize: Int,
    val nextPageToken: String?,
    val rowLimitReached: Boolean,
)

data class QueryStatusEventResponse(
    val eventId: String,
    val executionId: String,
    val datasourceId: String,
    val status: String,
    val message: String,
    val occurredAt: String,
)

data class QueryCsvExportPayload(
    val executionId: String,
    val datasourceId: String,
    val includeHeaders: Boolean,
    val rowCount: Int,
    val columns: List<QueryResultColumn>,
    val rows: List<List<String?>>,
)

data class QueryHistoryEntryResponse(
    val executionId: String,
    val actor: String,
    val datasourceId: String,
    val status: String,
    val message: String,
    val queryHash: String,
    val queryText: String?,
    val queryTextRedacted: Boolean,
    val errorSummary: String?,
    val rowCount: Int,
    val columnCount: Int,
    val rowLimitReached: Boolean,
    val maxRowsPerQuery: Int,
    val maxRuntimeSeconds: Int,
    val credentialProfile: String,
    val submittedAt: String,
    val startedAt: String?,
    val completedAt: String?,
    val durationMs: Long?,
)
