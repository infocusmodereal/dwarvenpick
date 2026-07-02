package com.dwarvenpick.app.resource

import com.dwarvenpick.app.persistence.PersistenceSchemaInitializer
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
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
class ResourceRepository(
    jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    @Suppress("unused") private val persistenceSchemaInitializer: PersistenceSchemaInitializer,
) {
    private val namedParameterJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
    private val resourceRowMapper = RowMapper { resultSet, _ -> resultSet.toResourceRecord() }
    private val resourceSummaryRowMapper = RowMapper { resultSet, _ -> resultSet.toResourceSummaryRecord() }
    private val versionRowMapper = RowMapper { resultSet, _ -> resultSet.toResourceVersionRecord() }
    private val tagsType = object : TypeReference<List<String>>() {}

    fun listResourceSummaries(
        username: String,
        groups: Set<String>,
        systemAdmin: Boolean,
        query: String?,
        scope: ResourceScope?,
        groupId: String?,
        datasourceId: String?,
        tag: String?,
        limit: Int,
        offset: Int,
    ): List<ResourceSummaryRecord> {
        val normalizedQuery = query?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val normalizedTag = tag?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        return namedParameterJdbcTemplate.query(
            """
            SELECT page.resource_id,
                   page.owner,
                   page.title,
                   page.sql_preview,
                   page.sql_length,
                   page.scope,
                   page.group_id,
                   page.folder_path,
                   page.datasource_id,
                   page.tags_json,
                   page.allow_group_edit,
                   page.created_at,
                   page.updated_at,
                   page.current_revision,
                   COUNT(v.version_id) AS version_count
            FROM (
              SELECT r.resource_id,
                     r.owner,
                     r.title,
                     CASE
                       WHEN CHAR_LENGTH(r.sql_text) > 240 THEN CONCAT(SUBSTR(r.sql_text, 1, 240), '...')
                       ELSE r.sql_text
                     END AS sql_preview,
                     CHAR_LENGTH(r.sql_text) AS sql_length,
                     r.scope,
                     r.group_id,
                     r.folder_path,
                     r.datasource_id,
                     r.tags_json,
                     r.allow_group_edit,
                     r.created_at,
                     r.updated_at,
                     r.current_revision
              FROM resource_scripts r
              WHERE (
                CAST(:queryLike AS VARCHAR) IS NULL OR
                LOWER(r.title) LIKE :queryLike ESCAPE '!' OR
                LOWER(r.folder_path) LIKE :queryLike ESCAPE '!' OR
                LOWER(COALESCE(r.datasource_id, '')) LIKE :queryLike ESCAPE '!' OR
                LOWER(r.owner) LIKE :queryLike ESCAPE '!' OR
                LOWER(r.sql_text) LIKE :queryLike ESCAPE '!' OR
                LOWER(COALESCE(r.group_id, '')) LIKE :queryLike ESCAPE '!' OR
                LOWER(r.tags_json) LIKE :queryLike ESCAPE '!'
              )
                AND (
                  :systemAdmin = 1 OR
                  r.owner = :username OR
                  (
                    r.scope = 'SHARED' AND
                    r.group_id IN (:groups)
                  )
                )
                AND (CAST(:scope AS VARCHAR) IS NULL OR r.scope = :scope)
                AND (CAST(:groupId AS VARCHAR) IS NULL OR r.group_id = :groupId)
                AND (CAST(:datasourceId AS VARCHAR) IS NULL OR r.datasource_id = :datasourceId)
                AND (CAST(:tagLike AS VARCHAR) IS NULL OR LOWER(r.tags_json) LIKE :tagLike ESCAPE '!')
              ORDER BY r.updated_at DESC, r.resource_id DESC
              LIMIT :limit OFFSET :offset
            ) page
            LEFT JOIN resource_script_versions v ON v.resource_id = page.resource_id
            GROUP BY page.resource_id,
                     page.owner,
                     page.title,
                     page.sql_preview,
                     page.sql_length,
                     page.scope,
                     page.group_id,
                     page.folder_path,
                     page.datasource_id,
                     page.tags_json,
                     page.allow_group_edit,
                     page.created_at,
                     page.updated_at,
                     page.current_revision
            ORDER BY page.updated_at DESC, page.resource_id DESC
            """.trimIndent(),
            mapOf(
                "username" to username,
                "groups" to groups.ifEmpty { setOf("__no_matching_groups__") },
                "systemAdmin" to systemAdmin.toIntFlag(),
                "queryLike" to normalizedQuery?.let { "%${it.escapeLikePattern()}%" },
                "scope" to scope?.name,
                "groupId" to groupId,
                "datasourceId" to datasourceId,
                "tagLike" to normalizedTag?.let { "%\"${it.escapeLikePattern()}\"%" },
                "limit" to limit.coerceAtLeast(1),
                "offset" to offset.coerceAtLeast(0),
            ),
            resourceSummaryRowMapper,
        )
    }

    fun listResources(): List<ResourceRecord> =
        namedParameterJdbcTemplate.query(
            """
            SELECT resource_id, owner, title, sql_text, scope, group_id, folder_path, datasource_id,
                   tags_json, allow_group_edit, created_at, updated_at, current_revision
            FROM resource_scripts
            ORDER BY updated_at DESC, resource_id DESC
            """.trimIndent(),
            emptyMap<String, Any>(),
            resourceRowMapper,
        )

    fun findResource(resourceId: String): ResourceRecord? =
        namedParameterJdbcTemplate
            .query(
                """
                SELECT resource_id, owner, title, sql_text, scope, group_id, folder_path, datasource_id,
                       tags_json, allow_group_edit, created_at, updated_at, current_revision
                FROM resource_scripts
                WHERE resource_id = :resourceId
                """.trimIndent(),
                mapOf("resourceId" to resourceId),
                resourceRowMapper,
            ).firstOrNull()

    fun listVersions(resourceId: String): List<ResourceVersionRecord> =
        namedParameterJdbcTemplate.query(
            """
            SELECT version_id, resource_id, revision, title, sql_text, scope, group_id, folder_path,
                   datasource_id, tags_json, allow_group_edit, action, saved_by, saved_at
            FROM resource_script_versions
            WHERE resource_id = :resourceId
            ORDER BY revision ASC
            """.trimIndent(),
            mapOf("resourceId" to resourceId),
            versionRowMapper,
        )

    fun countVersions(resourceId: String): Int =
        namedParameterJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM resource_script_versions
            WHERE resource_id = :resourceId
            """.trimIndent(),
            mapOf("resourceId" to resourceId),
            Int::class.java,
        ) ?: 0

    fun findVersion(
        resourceId: String,
        versionId: String,
    ): ResourceVersionRecord? =
        namedParameterJdbcTemplate
            .query(
                """
                SELECT version_id, resource_id, revision, title, sql_text, scope, group_id, folder_path,
                       datasource_id, tags_json, allow_group_edit, action, saved_by, saved_at
                FROM resource_script_versions
                WHERE resource_id = :resourceId
                  AND version_id = :versionId
                """.trimIndent(),
                mapOf("resourceId" to resourceId, "versionId" to versionId),
                versionRowMapper,
            ).firstOrNull()

    @Transactional
    fun saveResource(record: ResourceRecord) {
        namedParameterJdbcTemplate.update(
            "DELETE FROM resource_scripts WHERE resource_id = :resourceId",
            mapOf("resourceId" to record.resourceId),
        )
        namedParameterJdbcTemplate.update(
            """
            INSERT INTO resource_scripts (
              resource_id,
              owner,
              title,
              sql_text,
              scope,
              group_id,
              folder_path,
              datasource_id,
              tags_json,
              allow_group_edit,
              created_at,
              updated_at,
              current_revision
            ) VALUES (
              :resourceId,
              :owner,
              :title,
              :sqlText,
              :scope,
              :groupId,
              :folderPath,
              :datasourceId,
              :tagsJson,
              :allowGroupEdit,
              :createdAt,
              :updatedAt,
              :currentRevision
            )
            """.trimIndent(),
            record.toParameters(),
        )
    }

    fun saveVersion(record: ResourceVersionRecord) {
        namedParameterJdbcTemplate.update(
            "DELETE FROM resource_script_versions WHERE version_id = :versionId",
            mapOf("versionId" to record.versionId),
        )
        namedParameterJdbcTemplate.update(
            """
            INSERT INTO resource_script_versions (
              version_id,
              resource_id,
              revision,
              title,
              sql_text,
              scope,
              group_id,
              folder_path,
              datasource_id,
              tags_json,
              allow_group_edit,
              action,
              saved_by,
              saved_at
            ) VALUES (
              :versionId,
              :resourceId,
              :revision,
              :title,
              :sqlText,
              :scope,
              :groupId,
              :folderPath,
              :datasourceId,
              :tagsJson,
              :allowGroupEdit,
              :action,
              :savedBy,
              :savedAt
            )
            """.trimIndent(),
            record.toParameters(),
        )
    }

    @Transactional
    fun deleteResource(resourceId: String): Boolean {
        namedParameterJdbcTemplate.update(
            "DELETE FROM resource_script_versions WHERE resource_id = :resourceId",
            mapOf("resourceId" to resourceId),
        )
        return namedParameterJdbcTemplate.update(
            "DELETE FROM resource_scripts WHERE resource_id = :resourceId",
            mapOf("resourceId" to resourceId),
        ) > 0
    }

    @Transactional
    fun importSnapshotIfEmpty(snapshot: ResourceStorageSnapshot): Boolean {
        val existing =
            namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resource_scripts",
                emptyMap<String, Any>(),
                Int::class.java,
            ) ?: 0
        if (existing > 0) {
            return false
        }

        snapshot.resources.forEach { resource -> saveResource(resource) }
        snapshot.versions.values
            .flatten()
            .forEach { version -> saveVersion(version) }
        return snapshot.resources.isNotEmpty() || snapshot.versions.isNotEmpty()
    }

    @Transactional
    fun pruneVersions(
        cutoff: Instant,
        maxVersionsPerResource: Int,
    ): Int {
        val removedByAge =
            namedParameterJdbcTemplate.update(
                """
                DELETE FROM resource_script_versions
                WHERE saved_at < :cutoff
                  AND revision < COALESCE(
                    (
                      SELECT current_revision
                      FROM resource_scripts
                      WHERE resource_scripts.resource_id = resource_script_versions.resource_id
                    ),
                    revision
                  )
                """.trimIndent(),
                mapOf("cutoff" to cutoff.toTimestamp()),
            )

        val maxVersions = maxVersionsPerResource.coerceAtLeast(1)
        val removedByCount =
            listResources().sumOf { resource ->
                val versions = listVersions(resource.resourceId)
                val staleVersions =
                    versions
                        .filter { version -> version.revision < resource.currentRevision }
                        .dropLast(maxVersions)
                staleVersions.sumOf { version ->
                    namedParameterJdbcTemplate.update(
                        "DELETE FROM resource_script_versions WHERE version_id = :versionId",
                        mapOf("versionId" to version.versionId),
                    )
                }
            }

        return removedByAge + removedByCount
    }

    @Transactional
    fun clear() {
        namedParameterJdbcTemplate.update("DELETE FROM resource_script_versions", emptyMap<String, Any>())
        namedParameterJdbcTemplate.update("DELETE FROM resource_scripts", emptyMap<String, Any>())
    }

    private fun ResourceRecord.toParameters(): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("resourceId", resourceId)
            .addValue("owner", owner)
            .addValue("title", title)
            .addValue("sqlText", sql)
            .addValue("scope", scope.name)
            .addValue("groupId", groupId)
            .addValue("folderPath", folderPath)
            .addValue("datasourceId", datasourceId)
            .addValue("tagsJson", objectMapper.writeValueAsString(tags))
            .addValue("allowGroupEdit", allowGroupEdit)
            .addValue("createdAt", createdAt.toTimestamp())
            .addValue("updatedAt", updatedAt.toTimestamp())
            .addValue("currentRevision", currentRevision)

    private fun ResourceVersionRecord.toParameters(): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("versionId", versionId)
            .addValue("resourceId", resourceId)
            .addValue("revision", revision)
            .addValue("title", title)
            .addValue("sqlText", sql)
            .addValue("scope", scope.name)
            .addValue("groupId", groupId)
            .addValue("folderPath", folderPath)
            .addValue("datasourceId", datasourceId)
            .addValue("tagsJson", objectMapper.writeValueAsString(tags))
            .addValue("allowGroupEdit", allowGroupEdit)
            .addValue("action", action.name)
            .addValue("savedBy", savedBy)
            .addValue("savedAt", savedAt.toTimestamp())

    private fun ResultSet.toResourceRecord(): ResourceRecord =
        ResourceRecord(
            resourceId = getString("resource_id"),
            owner = getString("owner"),
            title = getString("title"),
            sql = getString("sql_text"),
            scope = ResourceScope.valueOf(getString("scope")),
            groupId = getString("group_id"),
            folderPath = getString("folder_path"),
            datasourceId = getString("datasource_id"),
            tags = objectMapper.readValue(getString("tags_json"), tagsType),
            allowGroupEdit = getBoolean("allow_group_edit"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            currentRevision = getInt("current_revision"),
        )

    private fun ResultSet.toResourceSummaryRecord(): ResourceSummaryRecord =
        ResourceSummaryRecord(
            resourceId = getString("resource_id"),
            owner = getString("owner"),
            title = getString("title"),
            sqlPreview = getString("sql_preview"),
            sqlLength = getInt("sql_length"),
            scope = ResourceScope.valueOf(getString("scope")),
            groupId = getString("group_id"),
            folderPath = getString("folder_path"),
            datasourceId = getString("datasource_id"),
            tags = objectMapper.readValue(getString("tags_json"), tagsType),
            allowGroupEdit = getBoolean("allow_group_edit"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            currentRevision = getInt("current_revision"),
            versionCount = getInt("version_count"),
        )

    private fun ResultSet.toResourceVersionRecord(): ResourceVersionRecord =
        ResourceVersionRecord(
            versionId = getString("version_id"),
            resourceId = getString("resource_id"),
            revision = getInt("revision"),
            title = getString("title"),
            sql = getString("sql_text"),
            scope = ResourceScope.valueOf(getString("scope")),
            groupId = getString("group_id"),
            folderPath = getString("folder_path"),
            datasourceId = getString("datasource_id"),
            tags = objectMapper.readValue(getString("tags_json"), tagsType),
            allowGroupEdit = getBoolean("allow_group_edit"),
            action = ResourceVersionAction.valueOf(getString("action")),
            savedBy = getString("saved_by"),
            savedAt = getTimestamp("saved_at").toInstant(),
        )

    private fun Instant.toTimestamp(): Timestamp = Timestamp.from(this)

    private fun Boolean.toIntFlag(): Int = if (this) 1 else 0

    private fun String.escapeLikePattern(): String =
        buildString {
            this@escapeLikePattern.forEach { character ->
                if (character == '!' || character == '%' || character == '_') {
                    append('!')
                }
                append(character)
            }
        }
}
