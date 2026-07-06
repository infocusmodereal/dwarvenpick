package com.dwarvenpick.app.query

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

@Repository
class QueryHistoryRepository(
    jdbcTemplate: JdbcTemplate,
) {
    private val namedParameterJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
    private val rowMapper = RowMapper { resultSet, _ -> resultSet.toQueryHistoryRecord() }

    @Transactional
    fun save(record: QueryHistoryRecord) {
        val existing = findByExecutionId(record.executionId)
        val recordToPersist =
            if (existing?.queryTextRedacted == true) {
                record.copy(queryText = null, queryTextRedacted = true)
            } else {
                record.copy(queryText = existing?.queryText ?: record.queryText)
            }

        namedParameterJdbcTemplate.update(
            "DELETE FROM query_history WHERE execution_id = :executionId",
            mapOf("executionId" to record.executionId),
        )
        namedParameterJdbcTemplate.update(insertSql, recordToPersist.toSqlParameters())
    }

    fun list(filter: QueryHistoryFilter): List<QueryHistoryRecord> {
        val parameters = MapSqlParameterSource()
        val predicates = mutableListOf<String>()
        val actorFilter = filter.actorFilter?.trim()?.takeIf { it.isNotBlank() }
        val datasourceId = filter.datasourceId?.trim()?.takeIf { it.isNotBlank() }
        val orderDirection =
            when (filter.sortOrder) {
                QueryHistorySortOrder.NEWEST -> "DESC"
                QueryHistorySortOrder.OLDEST -> "ASC"
            }

        if (filter.isSystemAdmin) {
            if (actorFilter != null) {
                predicates.add("actor = :actorFilter")
                parameters.addValue("actorFilter", actorFilter)
            }
        } else {
            predicates.add("actor = :actor")
            parameters.addValue("actor", filter.actor)
        }
        if (datasourceId != null) {
            predicates.add("datasource_id = :datasourceId")
            parameters.addValue("datasourceId", datasourceId)
        }
        if (filter.status != null) {
            predicates.add("status = :status")
            parameters.addValue("status", filter.status.name)
        }
        if (filter.from != null) {
            predicates.add("submitted_at >= :from")
            parameters.addValue("from", filter.from.toTimestamp())
        }
        if (filter.to != null) {
            predicates.add("submitted_at <= :to")
            parameters.addValue("to", filter.to.toTimestamp())
        }
        parameters
            .addValue("limit", filter.limit.coerceIn(1, 1001))
            .addValue("offset", filter.offset.coerceAtLeast(0))
        val whereClause =
            predicates
                .takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "WHERE ", separator = "\n              AND ")
                ?: ""

        return namedParameterJdbcTemplate.query(
            """
            SELECT
              execution_id,
              actor,
              datasource_id,
              credential_profile,
              justification,
              query_hash,
              query_text,
              query_text_redacted,
              status,
              message,
              error_summary,
              row_count,
              column_count,
              row_limit_reached,
              max_rows_per_query,
              max_runtime_seconds,
              submitted_at,
              started_at,
              completed_at
            FROM query_history
            $whereClause
            ORDER BY submitted_at $orderDirection, execution_id $orderDirection
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
            parameters,
            rowMapper,
        )
    }

    fun pruneOlderThan(cutoff: Instant): Int =
        namedParameterJdbcTemplate.update(
            "DELETE FROM query_history WHERE COALESCE(completed_at, submitted_at) < :cutoff",
            mapOf("cutoff" to cutoff.toTimestamp()),
        )

    fun redactQueryTextOlderThan(cutoff: Instant): Int =
        namedParameterJdbcTemplate.update(
            """
            UPDATE query_history
            SET query_text = NULL,
                query_text_redacted = TRUE
            WHERE query_text_redacted = FALSE
              AND submitted_at < :cutoff
            """.trimIndent(),
            mapOf("cutoff" to cutoff.toTimestamp()),
        )

    fun clear() {
        namedParameterJdbcTemplate.update("DELETE FROM query_history", emptyMap<String, Any>())
    }

    private fun findByExecutionId(executionId: String): QueryHistoryRecord? =
        try {
            namedParameterJdbcTemplate.queryForObject(
                """
                SELECT
                  execution_id,
                  actor,
                  datasource_id,
                  credential_profile,
                  justification,
                  query_hash,
                  query_text,
                  query_text_redacted,
                  status,
                  message,
                  error_summary,
                  row_count,
                  column_count,
                  row_limit_reached,
                  max_rows_per_query,
                  max_runtime_seconds,
                  submitted_at,
                  started_at,
                  completed_at
                FROM query_history
                WHERE execution_id = :executionId
                """.trimIndent(),
                mapOf("executionId" to executionId),
                rowMapper,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    private fun QueryHistoryRecord.toSqlParameters(): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("executionId", executionId)
            .addValue("actor", actor)
            .addValue("datasourceId", datasourceId)
            .addValue("credentialProfile", credentialProfile)
            .addValue("justification", justification)
            .addValue("queryHash", queryHash)
            .addValue("queryText", queryText)
            .addValue("queryTextRedacted", queryTextRedacted)
            .addValue("status", status.name)
            .addValue("message", message)
            .addValue("errorSummary", errorSummary)
            .addValue("rowCount", rowCount)
            .addValue("columnCount", columnCount)
            .addValue("rowLimitReached", rowLimitReached)
            .addValue("maxRowsPerQuery", maxRowsPerQuery)
            .addValue("maxRuntimeSeconds", maxRuntimeSeconds)
            .addValue("submittedAt", submittedAt.toTimestamp())
            .addValue("startedAt", startedAt?.toTimestamp())
            .addValue("completedAt", completedAt?.toTimestamp())

    private fun ResultSet.toQueryHistoryRecord(): QueryHistoryRecord =
        QueryHistoryRecord(
            executionId = getString("execution_id"),
            actor = getString("actor"),
            datasourceId = getString("datasource_id"),
            credentialProfile = getString("credential_profile"),
            justification = getString("justification"),
            queryHash = getString("query_hash"),
            queryText = getString("query_text"),
            queryTextRedacted = getBoolean("query_text_redacted"),
            status = QueryExecutionStatus.valueOf(getString("status")),
            message = getString("message"),
            errorSummary = getString("error_summary"),
            rowCount = getInt("row_count"),
            columnCount = getInt("column_count"),
            rowLimitReached = getBoolean("row_limit_reached"),
            maxRowsPerQuery = getInt("max_rows_per_query"),
            maxRuntimeSeconds = getInt("max_runtime_seconds"),
            submittedAt = getRequiredInstant("submitted_at"),
            startedAt = getNullableInstant("started_at"),
            completedAt = getNullableInstant("completed_at"),
        )

    private fun ResultSet.getRequiredInstant(columnName: String): Instant =
        requireNotNull(getNullableInstant(columnName)) { "$columnName cannot be null." }

    private fun ResultSet.getNullableInstant(columnName: String): Instant? = getTimestamp(columnName)?.toInstant()

    private fun Instant.toTimestamp(): Timestamp = Timestamp.from(this)

    private companion object {
        val insertSql =
            """
            INSERT INTO query_history (
              execution_id,
              actor,
              datasource_id,
              credential_profile,
              justification,
              query_hash,
              query_text,
              query_text_redacted,
              status,
              message,
              error_summary,
              row_count,
              column_count,
              row_limit_reached,
              max_rows_per_query,
              max_runtime_seconds,
              submitted_at,
              started_at,
              completed_at
            ) VALUES (
              :executionId,
              :actor,
              :datasourceId,
              :credentialProfile,
              :justification,
              :queryHash,
              :queryText,
              :queryTextRedacted,
              :status,
              :message,
              :errorSummary,
              :rowCount,
              :columnCount,
              :rowLimitReached,
              :maxRowsPerQuery,
              :maxRuntimeSeconds,
              :submittedAt,
              :startedAt,
              :completedAt
            )
            """.trimIndent()
    }
}
