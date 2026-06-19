package com.dwarvenpick.app.query

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

data class PersistedQueryRuntimeRecord(
    val executionId: String,
    val actor: String,
    val ipAddress: String?,
    val datasourceId: String,
    val credentialProfile: String,
    val sql: String?,
    val sqlRedacted: Boolean,
    val queryHash: String,
    val maxRowsPerQuery: Int,
    val maxRuntimeSeconds: Int,
    val concurrencyLimit: Int,
    val scriptStatementCount: Int,
    val scriptStopOnError: Boolean,
    val scriptTransactionMode: ScriptTransactionMode,
    val scriptStatements: List<QueryScriptStatementSummary>,
    val status: QueryExecutionStatus,
    val message: String,
    val errorSummary: String?,
    val submittedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val rowLimitReached: Boolean,
    val columns: List<QueryResultColumn>,
    val rows: List<List<String?>>,
    val lastAccessedAt: Instant,
    val resultsExpired: Boolean,
    val cancelRequested: Boolean,
    val ownerInstanceId: String,
    val heartbeatAt: Instant,
)

@Repository
class QueryRuntimeRepository(
    jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val namedParameterJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
    private val rowMapper = RowMapper { resultSet, _ -> resultSet.toRecord() }
    private val columnsType = object : TypeReference<List<QueryResultColumn>>() {}
    private val rowsType = object : TypeReference<List<List<String?>>>() {}
    private val statementsType = object : TypeReference<List<QueryScriptStatementSummary>>() {}

    @Transactional
    fun save(record: PersistedQueryRuntimeRecord) {
        val existing = find(record.executionId)
        val recordToPersist =
            if (existing?.sqlRedacted == true) {
                record.copy(
                    sql = null,
                    sqlRedacted = true,
                    cancelRequested = record.cancelRequested || existing.cancelRequested,
                )
            } else {
                record.copy(
                    sql = existing?.sql ?: record.sql,
                    cancelRequested = record.cancelRequested || (existing?.cancelRequested == true),
                )
            }

        namedParameterJdbcTemplate.update(
            "DELETE FROM query_runtime_executions WHERE execution_id = :executionId",
            mapOf("executionId" to record.executionId),
        )
        namedParameterJdbcTemplate.update(insertSql, recordToPersist.toParameters())
    }

    fun find(executionId: String): PersistedQueryRuntimeRecord? =
        try {
            namedParameterJdbcTemplate.queryForObject(
                selectSql("WHERE execution_id = :executionId"),
                mapOf("executionId" to executionId),
                rowMapper,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    fun listActive(
        actor: String,
        isSystemAdmin: Boolean,
        datasourceId: String?,
        actorFilter: String?,
    ): List<PersistedQueryRuntimeRecord> {
        val parameters = MapSqlParameterSource()
        val predicates =
            mutableListOf(
                "status IN (:activeStatuses)",
            )
        parameters.addValue("activeStatuses", listOf(QueryExecutionStatus.QUEUED.name, QueryExecutionStatus.RUNNING.name))

        if (!isSystemAdmin) {
            predicates.add("actor = :actor")
            parameters.addValue("actor", actor)
        } else if (!actorFilter.isNullOrBlank()) {
            predicates.add("actor = :actorFilter")
            parameters.addValue("actorFilter", actorFilter.trim())
        }

        if (!datasourceId.isNullOrBlank()) {
            predicates.add("datasource_id = :datasourceId")
            parameters.addValue("datasourceId", datasourceId.trim())
        }

        return namedParameterJdbcTemplate.query(
            selectSql(predicates.joinToString(prefix = "WHERE ", separator = "\n  AND ")),
            parameters,
            rowMapper,
        )
    }

    fun countActive(status: QueryExecutionStatus): Int =
        namedParameterJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM query_runtime_executions WHERE status = :status",
            mapOf("status" to status.name),
            Int::class.java,
        ) ?: 0

    fun updateLastAccessed(
        executionId: String,
        accessedAt: Instant,
    ) {
        namedParameterJdbcTemplate.update(
            """
            UPDATE query_runtime_executions
            SET last_accessed_at = :accessedAt
            WHERE execution_id = :executionId
            """.trimIndent(),
            mapOf("executionId" to executionId, "accessedAt" to accessedAt.toTimestamp()),
        )
    }

    fun updateHeartbeat(
        executionId: String,
        ownerInstanceId: String,
        heartbeatAt: Instant,
    ) {
        namedParameterJdbcTemplate.update(
            """
            UPDATE query_runtime_executions
            SET owner_instance_id = :ownerInstanceId,
                heartbeat_at = :heartbeatAt
            WHERE execution_id = :executionId
              AND status IN ('QUEUED', 'RUNNING')
            """.trimIndent(),
            mapOf(
                "executionId" to executionId,
                "ownerInstanceId" to ownerInstanceId,
                "heartbeatAt" to heartbeatAt.toTimestamp(),
            ),
        )
    }

    fun markCancelRequested(executionId: String): Boolean =
        namedParameterJdbcTemplate.update(
            """
            UPDATE query_runtime_executions
            SET cancel_requested = TRUE,
                message = CASE
                  WHEN status IN ('QUEUED', 'RUNNING') THEN 'Cancellation requested.'
                  ELSE message
                END
            WHERE execution_id = :executionId
            """.trimIndent(),
            mapOf("executionId" to executionId),
        ) > 0

    fun isCancelRequested(executionId: String): Boolean =
        namedParameterJdbcTemplate.queryForObject(
            "SELECT cancel_requested FROM query_runtime_executions WHERE execution_id = :executionId",
            mapOf("executionId" to executionId),
            Boolean::class.java,
        ) ?: false

    fun expireResults(
        executionId: String,
        message: String,
    ): Int =
        namedParameterJdbcTemplate.update(
            """
            UPDATE query_runtime_executions
            SET rows_json = '[]',
                columns_json = '[]',
                row_count = 0,
                column_count = 0,
                results_expired = TRUE,
                message = :message
            WHERE execution_id = :executionId
            """.trimIndent(),
            mapOf("executionId" to executionId, "message" to message),
        )

    fun pruneOlderThan(cutoff: Instant): Int =
        namedParameterJdbcTemplate.update(
            "DELETE FROM query_runtime_executions WHERE COALESCE(completed_at, submitted_at) < :cutoff",
            mapOf("cutoff" to cutoff.toTimestamp()),
        )

    fun redactQueryTextOlderThan(cutoff: Instant): Int =
        namedParameterJdbcTemplate.update(
            """
            UPDATE query_runtime_executions
            SET sql_text = NULL,
                sql_text_redacted = TRUE
            WHERE sql_text_redacted = FALSE
              AND submitted_at < :cutoff
            """.trimIndent(),
            mapOf("cutoff" to cutoff.toTimestamp()),
        )

    fun markStaleActiveExecutions(
        cutoff: Instant,
        message: String,
    ): Int =
        namedParameterJdbcTemplate.update(
            """
            UPDATE query_runtime_executions
            SET status = 'CANCELED',
                completed_at = :completedAt,
                cancel_requested = TRUE,
                message = :message
            WHERE status IN ('QUEUED', 'RUNNING')
              AND heartbeat_at < :cutoff
            """.trimIndent(),
            mapOf(
                "completedAt" to Instant.now().toTimestamp(),
                "cutoff" to cutoff.toTimestamp(),
                "message" to message,
            ),
        )

    fun clear() {
        namedParameterJdbcTemplate.update("DELETE FROM query_runtime_executions", emptyMap<String, Any>())
    }

    private fun PersistedQueryRuntimeRecord.toParameters(): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("executionId", executionId)
            .addValue("actor", actor)
            .addValue("ipAddress", ipAddress)
            .addValue("datasourceId", datasourceId)
            .addValue("credentialProfile", credentialProfile)
            .addValue("queryHash", queryHash)
            .addValue("sqlText", sql)
            .addValue("sqlTextRedacted", sqlRedacted)
            .addValue("status", status.name)
            .addValue("message", message)
            .addValue("errorSummary", errorSummary)
            .addValue("rowCount", rows.size)
            .addValue("columnCount", columns.size)
            .addValue("rowLimitReached", rowLimitReached)
            .addValue("maxRowsPerQuery", maxRowsPerQuery)
            .addValue("maxRuntimeSeconds", maxRuntimeSeconds)
            .addValue("concurrencyLimit", concurrencyLimit)
            .addValue("scriptStatementCount", scriptStatementCount)
            .addValue("scriptStopOnError", scriptStopOnError)
            .addValue("scriptTransactionMode", scriptTransactionMode.name)
            .addValue("scriptStatementsJson", objectMapper.writeValueAsString(scriptStatements))
            .addValue("columnsJson", objectMapper.writeValueAsString(columns))
            .addValue("rowsJson", objectMapper.writeValueAsString(rows))
            .addValue("submittedAt", submittedAt.toTimestamp())
            .addValue("startedAt", startedAt?.toTimestamp())
            .addValue("completedAt", completedAt?.toTimestamp())
            .addValue("lastAccessedAt", lastAccessedAt.toTimestamp())
            .addValue("resultsExpired", resultsExpired)
            .addValue("cancelRequested", cancelRequested)
            .addValue("ownerInstanceId", ownerInstanceId)
            .addValue("heartbeatAt", heartbeatAt.toTimestamp())

    private fun ResultSet.toRecord(): PersistedQueryRuntimeRecord =
        PersistedQueryRuntimeRecord(
            executionId = getString("execution_id"),
            actor = getString("actor"),
            ipAddress = getString("ip_address"),
            datasourceId = getString("datasource_id"),
            credentialProfile = getString("credential_profile"),
            queryHash = getString("query_hash"),
            sql = getString("sql_text"),
            sqlRedacted = getBoolean("sql_text_redacted"),
            status = QueryExecutionStatus.valueOf(getString("status")),
            message = getString("message"),
            errorSummary = getString("error_summary"),
            rowLimitReached = getBoolean("row_limit_reached"),
            maxRowsPerQuery = getInt("max_rows_per_query"),
            maxRuntimeSeconds = getInt("max_runtime_seconds"),
            concurrencyLimit = getInt("concurrency_limit"),
            scriptStatementCount = getInt("script_statement_count"),
            scriptStopOnError = getBoolean("script_stop_on_error"),
            scriptTransactionMode = ScriptTransactionMode.valueOf(getString("script_transaction_mode")),
            scriptStatements = objectMapper.readValue(getString("script_statements_json"), statementsType),
            submittedAt = getRequiredInstant("submitted_at"),
            startedAt = getNullableInstant("started_at"),
            completedAt = getNullableInstant("completed_at"),
            columns = objectMapper.readValue(getString("columns_json"), columnsType),
            rows = objectMapper.readValue(getString("rows_json"), rowsType),
            lastAccessedAt = getRequiredInstant("last_accessed_at"),
            resultsExpired = getBoolean("results_expired"),
            cancelRequested = getBoolean("cancel_requested"),
            ownerInstanceId = getString("owner_instance_id"),
            heartbeatAt = getRequiredInstant("heartbeat_at"),
        )

    private fun selectSql(whereClause: String): String =
        """
        SELECT execution_id, actor, ip_address, datasource_id, credential_profile, query_hash, sql_text,
               sql_text_redacted, status, message, error_summary, row_count, column_count, row_limit_reached,
               max_rows_per_query, max_runtime_seconds, concurrency_limit, script_statement_count,
               script_stop_on_error, script_transaction_mode, script_statements_json, columns_json, rows_json,
               submitted_at, started_at, completed_at, last_accessed_at, results_expired, cancel_requested,
               owner_instance_id, heartbeat_at
        FROM query_runtime_executions
        $whereClause
        ORDER BY submitted_at ASC, execution_id ASC
        """.trimIndent()

    private fun ResultSet.getRequiredInstant(columnName: String): Instant =
        requireNotNull(getNullableInstant(columnName)) { "$columnName cannot be null." }

    private fun ResultSet.getNullableInstant(columnName: String): Instant? = getTimestamp(columnName)?.toInstant()

    private fun Instant.toTimestamp(): Timestamp = Timestamp.from(this)

    private companion object {
        val insertSql =
            """
            INSERT INTO query_runtime_executions (
              execution_id, actor, ip_address, datasource_id, credential_profile, query_hash, sql_text,
              sql_text_redacted, status, message, error_summary, row_count, column_count, row_limit_reached,
              max_rows_per_query, max_runtime_seconds, concurrency_limit, script_statement_count,
              script_stop_on_error, script_transaction_mode, script_statements_json, columns_json, rows_json,
              submitted_at, started_at, completed_at, last_accessed_at, results_expired, cancel_requested,
              owner_instance_id, heartbeat_at
            ) VALUES (
              :executionId, :actor, :ipAddress, :datasourceId, :credentialProfile, :queryHash, :sqlText,
              :sqlTextRedacted, :status, :message, :errorSummary, :rowCount, :columnCount, :rowLimitReached,
              :maxRowsPerQuery, :maxRuntimeSeconds, :concurrencyLimit, :scriptStatementCount,
              :scriptStopOnError, :scriptTransactionMode, :scriptStatementsJson, :columnsJson, :rowsJson,
              :submittedAt, :startedAt, :completedAt, :lastAccessedAt, :resultsExpired, :cancelRequested,
              :ownerInstanceId, :heartbeatAt
            )
            """.trimIndent()
    }
}
