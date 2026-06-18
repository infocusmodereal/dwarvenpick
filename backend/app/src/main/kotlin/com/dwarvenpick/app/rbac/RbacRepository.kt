package com.dwarvenpick.app.rbac

import com.dwarvenpick.app.persistence.PersistenceSchemaInitializer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet

@Repository
class RbacRepository(
    jdbcTemplate: JdbcTemplate,
    @Suppress("unused") private val persistenceSchemaInitializer: PersistenceSchemaInitializer,
) {
    private val namedParameterJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
    private val groupRowMapper = RowMapper { resultSet, _ -> resultSet.toGroupRecord() }
    private val accessRowMapper = RowMapper { resultSet, _ -> resultSet.toDatasourceAccessRecord() }

    fun listGroups(): List<GroupRecord> =
        namedParameterJdbcTemplate.query(
            """
            SELECT group_id, group_name, description
            FROM rbac_groups
            ORDER BY group_name
            """.trimIndent(),
            emptyMap<String, Any>(),
            groupRowMapper,
        )

    fun findGroup(groupId: String): GroupRecord? =
        namedParameterJdbcTemplate
            .query(
                """
                SELECT group_id, group_name, description
                FROM rbac_groups
                WHERE group_id = :groupId
                """.trimIndent(),
                mapOf("groupId" to groupId),
                groupRowMapper,
            ).firstOrNull()

    @Transactional
    fun saveGroup(group: GroupRecord) {
        namedParameterJdbcTemplate.update(
            "DELETE FROM rbac_groups WHERE group_id = :groupId",
            mapOf("groupId" to group.id),
        )
        namedParameterJdbcTemplate.update(
            """
            INSERT INTO rbac_groups (group_id, group_name, description)
            VALUES (:groupId, :groupName, :description)
            """.trimIndent(),
            mapOf(
                "groupId" to group.id,
                "groupName" to group.name,
                "description" to group.description,
            ),
        )
        namedParameterJdbcTemplate.update(
            "DELETE FROM rbac_group_members WHERE group_id = :groupId",
            mapOf("groupId" to group.id),
        )
        group.members.sorted().forEach { username ->
            addMember(group.id, username)
        }
    }

    fun addMember(
        groupId: String,
        username: String,
    ) {
        namedParameterJdbcTemplate.update(
            """
            DELETE FROM rbac_group_members
            WHERE group_id = :groupId
              AND username = :username
            """.trimIndent(),
            mapOf("groupId" to groupId, "username" to username),
        )
        namedParameterJdbcTemplate.update(
            """
            INSERT INTO rbac_group_members (group_id, username)
            VALUES (:groupId, :username)
            """.trimIndent(),
            mapOf("groupId" to groupId, "username" to username),
        )
    }

    fun removeMember(
        groupId: String,
        username: String,
    ) {
        namedParameterJdbcTemplate.update(
            """
            DELETE FROM rbac_group_members
            WHERE group_id = :groupId
              AND username = :username
            """.trimIndent(),
            mapOf("groupId" to groupId, "username" to username),
        )
    }

    @Transactional
    fun deleteGroup(groupId: String): GroupRecord? {
        val group = findGroup(groupId) ?: return null
        namedParameterJdbcTemplate.update(
            "DELETE FROM rbac_datasource_access WHERE group_id = :groupId",
            mapOf("groupId" to groupId),
        )
        namedParameterJdbcTemplate.update(
            "DELETE FROM rbac_group_members WHERE group_id = :groupId",
            mapOf("groupId" to groupId),
        )
        namedParameterJdbcTemplate.update(
            "DELETE FROM rbac_groups WHERE group_id = :groupId",
            mapOf("groupId" to groupId),
        )
        return group
    }

    fun listDatasourceAccess(groupId: String? = null): List<DatasourceAccessRecord> {
        val whereClause = if (groupId == null) "" else "WHERE group_id = :groupId"
        return namedParameterJdbcTemplate.query(
            """
            SELECT group_id, datasource_id, can_query, can_export, read_only, max_rows_per_query,
                   max_runtime_seconds, concurrency_limit, credential_profile
            FROM rbac_datasource_access
            $whereClause
            ORDER BY group_id, datasource_id
            """.trimIndent(),
            groupId?.let { mapOf("groupId" to it) } ?: emptyMap<String, Any>(),
            accessRowMapper,
        )
    }

    fun saveDatasourceAccess(record: DatasourceAccessRecord) {
        namedParameterJdbcTemplate.update(
            """
            DELETE FROM rbac_datasource_access
            WHERE group_id = :groupId
              AND datasource_id = :datasourceId
            """.trimIndent(),
            mapOf("groupId" to record.groupId, "datasourceId" to record.datasourceId),
        )
        namedParameterJdbcTemplate.update(
            """
            INSERT INTO rbac_datasource_access (
              group_id,
              datasource_id,
              can_query,
              can_export,
              read_only,
              max_rows_per_query,
              max_runtime_seconds,
              concurrency_limit,
              credential_profile
            ) VALUES (
              :groupId,
              :datasourceId,
              :canQuery,
              :canExport,
              :readOnly,
              :maxRowsPerQuery,
              :maxRuntimeSeconds,
              :concurrencyLimit,
              :credentialProfile
            )
            """.trimIndent(),
            record.toParameters(),
        )
    }

    fun deleteDatasourceAccess(
        groupId: String,
        datasourceId: String,
    ): Boolean =
        namedParameterJdbcTemplate.update(
            """
            DELETE FROM rbac_datasource_access
            WHERE group_id = :groupId
              AND datasource_id = :datasourceId
            """.trimIndent(),
            mapOf("groupId" to groupId, "datasourceId" to datasourceId),
        ) > 0

    @Transactional
    fun clear() {
        namedParameterJdbcTemplate.update("DELETE FROM rbac_datasource_access", emptyMap<String, Any>())
        namedParameterJdbcTemplate.update("DELETE FROM rbac_group_members", emptyMap<String, Any>())
        namedParameterJdbcTemplate.update("DELETE FROM rbac_groups", emptyMap<String, Any>())
    }

    private fun DatasourceAccessRecord.toParameters(): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("groupId", groupId)
            .addValue("datasourceId", datasourceId)
            .addValue("canQuery", canQuery)
            .addValue("canExport", canExport)
            .addValue("readOnly", readOnly)
            .addValue("maxRowsPerQuery", maxRowsPerQuery)
            .addValue("maxRuntimeSeconds", maxRuntimeSeconds)
            .addValue("concurrencyLimit", concurrencyLimit)
            .addValue("credentialProfile", credentialProfile)

    private fun ResultSet.toGroupRecord(): GroupRecord {
        val groupId = getString("group_id")
        return GroupRecord(
            id = groupId,
            name = getString("group_name"),
            description = getString("description"),
            members =
                namedParameterJdbcTemplate
                    .queryForList(
                        "SELECT username FROM rbac_group_members WHERE group_id = :groupId ORDER BY username",
                        mapOf("groupId" to groupId),
                        String::class.java,
                    ).toMutableSet(),
        )
    }

    private fun ResultSet.toDatasourceAccessRecord(): DatasourceAccessRecord =
        DatasourceAccessRecord(
            groupId = getString("group_id"),
            datasourceId = getString("datasource_id"),
            canQuery = getBoolean("can_query"),
            canExport = getBoolean("can_export"),
            readOnly = getBoolean("read_only"),
            maxRowsPerQuery = nullableInt("max_rows_per_query"),
            maxRuntimeSeconds = nullableInt("max_runtime_seconds"),
            concurrencyLimit = nullableInt("concurrency_limit"),
            credentialProfile = getString("credential_profile"),
        )

    private fun ResultSet.nullableInt(columnName: String): Int? {
        val value = getInt(columnName)
        return if (wasNull()) null else value
    }
}
