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

    private fun Instant.toTimestamp(): Timestamp = Timestamp.from(this)
}
