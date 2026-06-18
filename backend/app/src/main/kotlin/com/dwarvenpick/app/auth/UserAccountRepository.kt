package com.dwarvenpick.app.auth

import com.dwarvenpick.app.persistence.PersistenceSchemaInitializer
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
class UserAccountRepository(
    jdbcTemplate: JdbcTemplate,
    @Suppress("unused") private val persistenceSchemaInitializer: PersistenceSchemaInitializer,
) {
    private val namedParameterJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
    private val rowMapper = RowMapper { resultSet, _ -> resultSet.toUserAccount() }

    fun list(): List<UserAccount> =
        namedParameterJdbcTemplate.query(
            """
            SELECT username, display_name, email, password_hash, provider, enabled, temporary_password
            FROM app_users
            ORDER BY username
            """.trimIndent(),
            emptyMap<String, Any>(),
            rowMapper,
        )

    fun find(username: String): UserAccount? =
        namedParameterJdbcTemplate
            .query(
                """
                SELECT username, display_name, email, password_hash, provider, enabled, temporary_password
                FROM app_users
                WHERE username = :username
                """.trimIndent(),
                mapOf("username" to username),
                rowMapper,
            ).firstOrNull()

    @Transactional
    fun save(account: UserAccount) {
        val now = Instant.now()
        val existing = find(account.username)
        if (existing == null) {
            namedParameterJdbcTemplate.update(
                """
                INSERT INTO app_users (
                  username,
                  display_name,
                  email,
                  password_hash,
                  provider,
                  enabled,
                  temporary_password,
                  created_at,
                  updated_at,
                  last_seen_at
                ) VALUES (
                  :username,
                  :displayName,
                  :email,
                  :passwordHash,
                  :provider,
                  :enabled,
                  :temporaryPassword,
                  :createdAt,
                  :updatedAt,
                  :lastSeenAt
                )
                """.trimIndent(),
                account.toParameters(now, createdAt = now),
            )
        } else {
            namedParameterJdbcTemplate.update(
                """
                UPDATE app_users
                SET display_name = :displayName,
                    email = :email,
                    password_hash = :passwordHash,
                    provider = :provider,
                    enabled = :enabled,
                    temporary_password = :temporaryPassword,
                    updated_at = :updatedAt,
                    last_seen_at = :lastSeenAt
                WHERE username = :username
                """.trimIndent(),
                account.toParameters(now),
            )
        }

        namedParameterJdbcTemplate.update(
            "DELETE FROM app_user_roles WHERE username = :username",
            mapOf("username" to account.username),
        )
        account.roles.sorted().forEach { role ->
            namedParameterJdbcTemplate.update(
                """
                INSERT INTO app_user_roles (username, role_name)
                VALUES (:username, :roleName)
                """.trimIndent(),
                mapOf("username" to account.username, "roleName" to role),
            )
        }

        namedParameterJdbcTemplate.update(
            "DELETE FROM app_user_groups WHERE username = :username",
            mapOf("username" to account.username),
        )
        account.groups.sorted().forEach { groupId ->
            namedParameterJdbcTemplate.update(
                """
                INSERT INTO app_user_groups (username, group_id)
                VALUES (:username, :groupId)
                """.trimIndent(),
                mapOf("username" to account.username, "groupId" to groupId),
            )
        }
    }

    @Transactional
    fun clear() {
        namedParameterJdbcTemplate.update("DELETE FROM app_user_groups", emptyMap<String, Any>())
        namedParameterJdbcTemplate.update("DELETE FROM app_user_roles", emptyMap<String, Any>())
        namedParameterJdbcTemplate.update("DELETE FROM app_users", emptyMap<String, Any>())
    }

    private fun UserAccount.toParameters(
        now: Instant,
        createdAt: Instant? = null,
    ): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("username", username)
            .addValue("displayName", displayName)
            .addValue("email", email)
            .addValue("passwordHash", passwordHash)
            .addValue("provider", provider.name)
            .addValue("enabled", enabled)
            .addValue("temporaryPassword", temporaryPassword)
            .addValue("createdAt", createdAt?.toTimestamp())
            .addValue("updatedAt", now.toTimestamp())
            .addValue("lastSeenAt", now.toTimestamp())

    private fun ResultSet.toUserAccount(): UserAccount {
        val username = getString("username")
        return UserAccount(
            username = username,
            displayName = getString("display_name"),
            email = getString("email"),
            passwordHash = getString("password_hash"),
            provider = AuthProvider.valueOf(getString("provider")),
            enabled = getBoolean("enabled"),
            temporaryPassword = getBoolean("temporary_password"),
            roles = setValues("SELECT role_name FROM app_user_roles WHERE username = :username", username).toMutableSet(),
            groups = setValues("SELECT group_id FROM app_user_groups WHERE username = :username", username).toMutableSet(),
        )
    }

    private fun setValues(
        sql: String,
        username: String,
    ): Set<String> =
        namedParameterJdbcTemplate
            .queryForList(sql, mapOf("username" to username), String::class.java)
            .toSet()

    private fun Instant.toTimestamp(): Timestamp = Timestamp.from(this)
}
