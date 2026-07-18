package com.dwarvenpick.app.query

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
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
    val defaultSchema: String?,
    val justification: String?,
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

data class PersistedQueryRuntimeMetadataRecord(
    val executionId: String,
    val actor: String,
    val ipAddress: String?,
    val datasourceId: String,
    val credentialProfile: String,
    val defaultSchema: String?,
    val justification: String?,
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
    val rowCount: Int,
    val columnCount: Int,
    val rowLimitReached: Boolean,
    val columns: List<QueryResultColumn>,
    val lastAccessedAt: Instant,
    val resultsExpired: Boolean,
    val cancelRequested: Boolean,
    val ownerInstanceId: String,
    val heartbeatAt: Instant,
)

private data class ExistingQueryRuntimeState(
    val sql: String?,
    val sqlRedacted: Boolean,
    val cancelRequested: Boolean,
)

private data class PersistedQueryResultPage(
    val startRow: Int,
    val rowCount: Int,
    val rows: List<List<String?>>,
)

@Repository
class QueryRuntimeRepository(
    jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val queryExecutionProperties: QueryExecutionProperties,
    private val queryResultPersistenceRepository: QueryResultPersistenceRepository,
) {
    private val namedParameterJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
    private val rowMapper = RowMapper { resultSet, _ -> resultSet.toRecord() }
    private val metadataRowMapper = RowMapper { resultSet, _ -> resultSet.toMetadataRecord() }
    private val columnsType = object : TypeReference<List<QueryResultColumn>>() {}
    private val rowsType = object : TypeReference<List<List<String?>>>() {}
    private val statementsType = object : TypeReference<List<QueryScriptStatementSummary>>() {}

    @Transactional
    fun save(record: PersistedQueryRuntimeRecord) {
        val existing = findExistingState(record.executionId)
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
        insertResultPages(recordToPersist)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun insertInitial(record: PersistedQueryRuntimeRecord) {
        namedParameterJdbcTemplate.update(insertSql, record.toParameters())
    }

    fun updateExecution(update: QueryRuntimeExecutionUpdate) = queryResultPersistenceRepository.updateExecution(update)

    @Transactional
    fun appendResultPage(
        executionId: String,
        ownerInstanceId: String,
        page: QueryResultPageSnapshot,
    ) = queryResultPersistenceRepository.appendResultPage(executionId, ownerInstanceId, page)

    @Transactional
    fun finalizeSucceededExecution(
        update: QueryRuntimeExecutionUpdate,
        finalPage: QueryResultPageSnapshot?,
    ) = queryResultPersistenceRepository.finalizeSucceededExecution(update, finalPage)

    @Transactional
    fun finalizeWithoutResults(update: QueryRuntimeExecutionUpdate) {
        queryResultPersistenceRepository.finalizeWithoutResults(update)
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

    fun findMetadata(executionId: String): PersistedQueryRuntimeMetadataRecord? =
        try {
            namedParameterJdbcTemplate.queryForObject(
                selectMetadataSql("WHERE execution_id = :executionId"),
                mapOf("executionId" to executionId),
                metadataRowMapper,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    fun fetchRows(
        executionId: String,
        startOffset: Int,
        limit: Int,
    ): List<List<String?>> {
        if (limit <= 0) {
            return emptyList()
        }
        val normalizedStartOffset = startOffset.coerceAtLeast(0)
        val endOffset = normalizedStartOffset + limit
        val pages =
            namedParameterJdbcTemplate.query(
                """
                SELECT start_row, row_count, rows_json
                FROM query_runtime_result_pages
                WHERE execution_id = :executionId
                  AND start_row < :endOffset
                  AND (start_row + row_count) > :startOffset
                ORDER BY start_row ASC
                """.trimIndent(),
                mapOf(
                    "executionId" to executionId,
                    "startOffset" to normalizedStartOffset,
                    "endOffset" to endOffset,
                ),
            ) { resultSet, _ ->
                PersistedQueryResultPage(
                    startRow = resultSet.getInt("start_row"),
                    rowCount = resultSet.getInt("row_count"),
                    rows = objectMapper.readValue(resultSet.getString("rows_json"), rowsType),
                )
            }

        return pages
            .flatMap { page ->
                val fromIndex = (normalizedStartOffset - page.startRow).coerceAtLeast(0)
                val toIndex = (endOffset - page.startRow).coerceAtMost(page.rows.size)
                if (fromIndex >= toIndex) {
                    emptyList()
                } else {
                    page.rows.subList(fromIndex, toIndex)
                }
            }.take(limit)
    }

    fun rowIterable(executionId: String): Iterable<List<String?>> =
        Iterable {
            object : Iterator<List<String?>> {
                private var nextPageIndex = 0
                private var currentRows: List<List<String?>> = emptyList()
                private var currentRowIndex = 0
                private var exhausted = false

                override fun hasNext(): Boolean {
                    while (!exhausted && currentRowIndex >= currentRows.size) {
                        if (!touchResultAccess(executionId, Instant.now())) {
                            throw QueryResultsExpiredException("Result session expired during export. Re-run the query.")
                        }
                        val nextPage = findResultPage(executionId, nextPageIndex)
                        nextPageIndex += 1
                        if (nextPage == null) {
                            exhausted = true
                        } else {
                            currentRows = nextPage.rows
                            currentRowIndex = 0
                        }
                    }
                    return currentRowIndex < currentRows.size
                }

                override fun next(): List<String?> {
                    if (!hasNext()) {
                        throw NoSuchElementException()
                    }
                    return currentRows[currentRowIndex++]
                }
            }
        }

    fun countResultPages(executionId: String): Int =
        namedParameterJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM query_runtime_result_pages WHERE execution_id = :executionId",
            mapOf("executionId" to executionId),
            Int::class.java,
        ) ?: 0

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

    fun listActiveMetadata(
        actor: String,
        isSystemAdmin: Boolean,
        datasourceId: String?,
        actorFilter: String?,
    ): List<PersistedQueryRuntimeMetadataRecord> {
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
            selectMetadataSql(predicates.joinToString(prefix = "WHERE ", separator = "\n  AND ")),
            parameters,
            metadataRowMapper,
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

    fun beginResultAccess(executionId: String): Boolean = queryResultPersistenceRepository.beginResultAccess(executionId)

    fun touchResultAccess(
        executionId: String,
        accessedAt: Instant,
    ): Boolean = queryResultPersistenceRepository.touchResultAccess(executionId, accessedAt)

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

    @Transactional
    fun expireResults(
        executionId: String,
        message: String,
    ): Int {
        namedParameterJdbcTemplate.update(
            "DELETE FROM query_runtime_result_pages WHERE execution_id = :executionId",
            mapOf("executionId" to executionId),
        )
        return namedParameterJdbcTemplate.update(
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
    }

    fun expireResultsIfIdle(
        executionId: String,
        cutoff: Instant,
        message: String,
    ): Boolean = queryResultPersistenceRepository.expireResultsIfIdle(executionId, cutoff, message)

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
    ): Int = queryResultPersistenceRepository.markStaleActiveExecutions(cutoff, message)

    fun clear() {
        namedParameterJdbcTemplate.update("DELETE FROM query_runtime_result_pages", emptyMap<String, Any>())
        namedParameterJdbcTemplate.update("DELETE FROM query_runtime_executions", emptyMap<String, Any>())
    }

    private fun insertResultPages(record: PersistedQueryRuntimeRecord) {
        if (record.rows.isEmpty()) {
            return
        }
        val chunkRows = queryExecutionProperties.resultChunkRows.coerceAtLeast(1)
        record.rows.chunked(chunkRows).forEachIndexed { pageIndex, rows ->
            val rowsJson = objectMapper.writeValueAsString(rows)
            namedParameterJdbcTemplate.update(
                """
                INSERT INTO query_runtime_result_pages (
                  execution_id, page_index, start_row, row_count, rows_json, byte_count, created_at
                ) VALUES (
                  :executionId, :pageIndex, :startRow, :rowCount, :rowsJson, :byteCount, :createdAt
                )
                """.trimIndent(),
                mapOf(
                    "executionId" to record.executionId,
                    "pageIndex" to pageIndex,
                    "startRow" to pageIndex * chunkRows,
                    "rowCount" to rows.size,
                    "rowsJson" to rowsJson,
                    "byteCount" to rowsJson.toByteArray(Charsets.UTF_8).size.toLong(),
                    "createdAt" to (record.completedAt ?: record.startedAt ?: record.submittedAt).toTimestamp(),
                ),
            )
        }
    }

    private fun findResultPage(
        executionId: String,
        pageIndex: Int,
    ): PersistedQueryResultPage? =
        try {
            namedParameterJdbcTemplate.queryForObject(
                """
                SELECT start_row, row_count, rows_json
                FROM query_runtime_result_pages
                WHERE execution_id = :executionId
                  AND page_index = :pageIndex
                """.trimIndent(),
                mapOf("executionId" to executionId, "pageIndex" to pageIndex),
            ) { resultSet, _ ->
                PersistedQueryResultPage(
                    startRow = resultSet.getInt("start_row"),
                    rowCount = resultSet.getInt("row_count"),
                    rows = objectMapper.readValue(resultSet.getString("rows_json"), rowsType),
                )
            }
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    private fun readPersistedRows(
        executionId: String,
        rowsJson: String,
        expectedRowCount: Int,
    ): List<List<String?>> {
        if (expectedRowCount <= 0) {
            return emptyList()
        }
        val pageRows = fetchRows(executionId, startOffset = 0, limit = expectedRowCount)
        if (pageRows.isNotEmpty()) {
            return pageRows
        }
        return objectMapper.readValue(rowsJson, rowsType)
    }

    private fun PersistedQueryRuntimeRecord.toParameters(): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("executionId", executionId)
            .addValue("actor", actor)
            .addValue("ipAddress", ipAddress)
            .addValue("datasourceId", datasourceId)
            .addValue("credentialProfile", credentialProfile)
            .addValue("defaultSchema", defaultSchema)
            .addValue("justification", justification)
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
            .addValue("rowsJson", EMPTY_ROWS_JSON)
            .addValue("submittedAt", submittedAt.toTimestamp())
            .addValue("startedAt", startedAt?.toTimestamp())
            .addValue("completedAt", completedAt?.toTimestamp())
            .addValue("lastAccessedAt", lastAccessedAt.toTimestamp())
            .addValue("resultsExpired", resultsExpired)
            .addValue("cancelRequested", cancelRequested)
            .addValue("ownerInstanceId", ownerInstanceId)
            .addValue("heartbeatAt", heartbeatAt.toTimestamp())

    private fun ResultSet.toRecord(): PersistedQueryRuntimeRecord {
        val executionId = getString("execution_id")
        val rowCount = getInt("row_count")
        return PersistedQueryRuntimeRecord(
            executionId = executionId,
            actor = getString("actor"),
            ipAddress = getString("ip_address"),
            datasourceId = getString("datasource_id"),
            credentialProfile = getString("credential_profile"),
            defaultSchema = getString("default_schema"),
            justification = getString("justification"),
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
            rows = readPersistedRows(executionId, getString("rows_json"), rowCount),
            lastAccessedAt = getRequiredInstant("last_accessed_at"),
            resultsExpired = getBoolean("results_expired"),
            cancelRequested = getBoolean("cancel_requested"),
            ownerInstanceId = getString("owner_instance_id"),
            heartbeatAt = getRequiredInstant("heartbeat_at"),
        )
    }

    private fun ResultSet.toMetadataRecord(): PersistedQueryRuntimeMetadataRecord =
        PersistedQueryRuntimeMetadataRecord(
            executionId = getString("execution_id"),
            actor = getString("actor"),
            ipAddress = getString("ip_address"),
            datasourceId = getString("datasource_id"),
            credentialProfile = getString("credential_profile"),
            defaultSchema = getString("default_schema"),
            justification = getString("justification"),
            queryHash = getString("query_hash"),
            sql = getString("sql_text"),
            sqlRedacted = getBoolean("sql_text_redacted"),
            status = QueryExecutionStatus.valueOf(getString("status")),
            message = getString("message"),
            errorSummary = getString("error_summary"),
            rowCount = getInt("row_count"),
            columnCount = getInt("column_count"),
            rowLimitReached = getBoolean("row_limit_reached"),
            columns = objectMapper.readValue(getString("columns_json"), columnsType),
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
            lastAccessedAt = getRequiredInstant("last_accessed_at"),
            resultsExpired = getBoolean("results_expired"),
            cancelRequested = getBoolean("cancel_requested"),
            ownerInstanceId = getString("owner_instance_id"),
            heartbeatAt = getRequiredInstant("heartbeat_at"),
        )

    private fun selectSql(whereClause: String): String =
        """
        SELECT execution_id, actor, ip_address, datasource_id, credential_profile, default_schema, justification, query_hash, sql_text,
               sql_text_redacted, status, message, error_summary, row_count, column_count, row_limit_reached,
               max_rows_per_query, max_runtime_seconds, concurrency_limit, script_statement_count,
               script_stop_on_error, script_transaction_mode, script_statements_json, columns_json, rows_json,
               submitted_at, started_at, completed_at, last_accessed_at, results_expired, cancel_requested,
               owner_instance_id, heartbeat_at
        FROM query_runtime_executions
        $whereClause
        ORDER BY submitted_at ASC, execution_id ASC
        """.trimIndent()

    private fun selectMetadataSql(whereClause: String): String =
        """
        SELECT execution_id, actor, ip_address, datasource_id, credential_profile, default_schema, justification, query_hash, sql_text,
               sql_text_redacted, status, message, error_summary, row_count, column_count, row_limit_reached,
               max_rows_per_query, max_runtime_seconds, concurrency_limit, script_statement_count,
               script_stop_on_error, script_transaction_mode, script_statements_json, columns_json,
               submitted_at, started_at, completed_at, last_accessed_at, results_expired, cancel_requested,
               owner_instance_id, heartbeat_at
        FROM query_runtime_executions
        $whereClause
        ORDER BY submitted_at ASC, execution_id ASC
        """.trimIndent()

    private fun findExistingState(executionId: String): ExistingQueryRuntimeState? =
        try {
            namedParameterJdbcTemplate.queryForObject(
                """
                SELECT sql_text, sql_text_redacted, cancel_requested
                FROM query_runtime_executions
                WHERE execution_id = :executionId
                """.trimIndent(),
                mapOf("executionId" to executionId),
            ) { resultSet, _ ->
                ExistingQueryRuntimeState(
                    sql = resultSet.getString("sql_text"),
                    sqlRedacted = resultSet.getBoolean("sql_text_redacted"),
                    cancelRequested = resultSet.getBoolean("cancel_requested"),
                )
            }
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    private fun ResultSet.getRequiredInstant(columnName: String): Instant =
        requireNotNull(getNullableInstant(columnName)) { "$columnName cannot be null." }

    private fun ResultSet.getNullableInstant(columnName: String): Instant? = getTimestamp(columnName)?.toInstant()

    private fun Instant.toTimestamp(): Timestamp = Timestamp.from(this)

    private companion object {
        private const val EMPTY_ROWS_JSON = "[]"
        val insertSql =
            """
            INSERT INTO query_runtime_executions (
              execution_id, actor, ip_address, datasource_id, credential_profile, default_schema, justification, query_hash, sql_text,
              sql_text_redacted, status, message, error_summary, row_count, column_count, row_limit_reached,
              max_rows_per_query, max_runtime_seconds, concurrency_limit, script_statement_count,
              script_stop_on_error, script_transaction_mode, script_statements_json, columns_json, rows_json,
              submitted_at, started_at, completed_at, last_accessed_at, results_expired, cancel_requested,
              owner_instance_id, heartbeat_at
            ) VALUES (
              :executionId, :actor, :ipAddress, :datasourceId, :credentialProfile, :defaultSchema, :justification, :queryHash, :sqlText,
              :sqlTextRedacted, :status, :message, :errorSummary, :rowCount, :columnCount, :rowLimitReached,
              :maxRowsPerQuery, :maxRuntimeSeconds, :concurrencyLimit, :scriptStatementCount,
              :scriptStopOnError, :scriptTransactionMode, :scriptStatementsJson, :columnsJson, :rowsJson,
              :submittedAt, :startedAt, :completedAt, :lastAccessedAt, :resultsExpired, :cancelRequested,
              :ownerInstanceId, :heartbeatAt
            )
            """.trimIndent()
    }
}
