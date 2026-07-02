package com.dwarvenpick.app.snippet

import com.dwarvenpick.app.persistence.PersistenceSchemaInitializer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Repository
class SnippetRepository(
    jdbcTemplate: JdbcTemplate,
    @Suppress("unused") private val persistenceSchemaInitializer: PersistenceSchemaInitializer,
) {
    private val namedParameterJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
    private val rowMapper = RowMapper { resultSet, _ -> resultSet.toSnippetRecord() }
    private val summaryRowMapper = RowMapper { resultSet, _ -> resultSet.toSnippetSummaryRecord() }

    fun list(): List<SnippetRecord> =
        namedParameterJdbcTemplate.query(
            """
            SELECT snippet_id, owner, group_id, title, sql_text, created_at, updated_at
            FROM snippets
            ORDER BY updated_at DESC, snippet_id DESC
            """.trimIndent(),
            emptyMap<String, Any>(),
            rowMapper,
        )

    fun listSummaries(
        username: String,
        groups: Set<String>,
        systemAdmin: Boolean,
        includePersonal: Boolean,
        includeGroup: Boolean,
        groupId: String?,
        exactTitle: String?,
        limit: Int,
        offset: Int,
    ): List<SnippetSummaryRecord> =
        namedParameterJdbcTemplate.query(
            """
            SELECT snippet_id,
                   owner,
                   group_id,
                   title,
                   CASE
                     WHEN CHAR_LENGTH(sql_text) > 240 THEN CONCAT(SUBSTR(sql_text, 1, 240), '...')
                     ELSE sql_text
                   END AS sql_preview,
                   CHAR_LENGTH(sql_text) AS sql_length,
                   created_at,
                   updated_at
            FROM snippets
            WHERE (
              (:includePersonal = 1 AND owner = :username) OR
              (
                :includeGroup = 1 AND
                group_id IS NOT NULL AND
                (:systemAdmin = 1 OR group_id IN (:groups))
              )
            )
              AND (:groupId IS NULL OR group_id = :groupId)
              AND (:exactTitle IS NULL OR LOWER(title) = :exactTitle)
            ORDER BY updated_at DESC, snippet_id DESC
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
            mapOf(
                "username" to username,
                "groups" to groups.ifEmpty { setOf("__no_matching_groups__") },
                "systemAdmin" to systemAdmin.toIntFlag(),
                "includePersonal" to includePersonal.toIntFlag(),
                "includeGroup" to includeGroup.toIntFlag(),
                "groupId" to groupId,
                "exactTitle" to exactTitle,
                "limit" to limit.coerceAtLeast(1),
                "offset" to offset.coerceAtLeast(0),
            ),
            summaryRowMapper,
        )

    fun find(snippetId: String): SnippetRecord? =
        namedParameterJdbcTemplate
            .query(
                """
                SELECT snippet_id, owner, group_id, title, sql_text, created_at, updated_at
                FROM snippets
                WHERE snippet_id = :snippetId
                """.trimIndent(),
                mapOf("snippetId" to snippetId),
                rowMapper,
            ).firstOrNull()

    fun save(record: SnippetRecord) {
        namedParameterJdbcTemplate.update(
            "DELETE FROM snippets WHERE snippet_id = :snippetId",
            mapOf("snippetId" to record.snippetId),
        )
        namedParameterJdbcTemplate.update(
            """
            INSERT INTO snippets (
              snippet_id,
              owner,
              group_id,
              title,
              sql_text,
              created_at,
              updated_at
            ) VALUES (
              :snippetId,
              :owner,
              :groupId,
              :title,
              :sqlText,
              :createdAt,
              :updatedAt
            )
            """.trimIndent(),
            record.toParameters(),
        )
    }

    fun delete(snippetId: String): Boolean =
        namedParameterJdbcTemplate.update(
            "DELETE FROM snippets WHERE snippet_id = :snippetId",
            mapOf("snippetId" to snippetId),
        ) > 0

    fun clear() {
        namedParameterJdbcTemplate.update("DELETE FROM snippets", emptyMap<String, Any>())
    }

    private fun SnippetRecord.toParameters(): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("snippetId", snippetId)
            .addValue("owner", owner)
            .addValue("groupId", groupId)
            .addValue("title", title)
            .addValue("sqlText", sql)
            .addValue("createdAt", createdAt.toTimestamp())
            .addValue("updatedAt", updatedAt.toTimestamp())

    private fun ResultSet.toSnippetRecord(): SnippetRecord =
        SnippetRecord(
            snippetId = getString("snippet_id"),
            owner = getString("owner"),
            groupId = getString("group_id"),
            title = getString("title"),
            sql = getString("sql_text"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    private fun ResultSet.toSnippetSummaryRecord(): SnippetSummaryRecord =
        SnippetSummaryRecord(
            snippetId = getString("snippet_id"),
            owner = getString("owner"),
            groupId = getString("group_id"),
            title = getString("title"),
            sqlPreview = getString("sql_preview"),
            sqlLength = getInt("sql_length"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    private fun Instant.toTimestamp(): Timestamp = Timestamp.from(this)

    private fun Boolean.toIntFlag(): Int = if (this) 1 else 0
}
