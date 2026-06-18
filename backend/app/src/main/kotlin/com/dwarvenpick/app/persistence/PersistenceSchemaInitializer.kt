package com.dwarvenpick.app.persistence

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class PersistenceSchemaInitializer(
    private val jdbcTemplate: JdbcTemplate,
) : InitializingBean {
    private val logger = LoggerFactory.getLogger(PersistenceSchemaInitializer::class.java)

    override fun afterPropertiesSet() {
        val databaseProduct =
            runCatching {
                jdbcTemplate.dataSource?.connection?.use { connection -> connection.metaData.databaseProductName ?: "" }
            }.getOrDefault("").orEmpty()

        if (!isSupportedDatabase(databaseProduct)) {
            logger.warn(
                "Persistent application state schema init is not supported for database '{}'. " +
                    "Ensure Dwarvenpick state tables exist before using persistent runtime state.",
                databaseProduct,
            )
            return
        }

        try {
            statements.forEach { statement -> jdbcTemplate.execute(statement) }
            logger.info("Persistent application state schema ensured for database '{}'.", databaseProduct)
        } catch (exception: Exception) {
            throw IllegalStateException(
                "Failed to initialize persistent application state schema for database '$databaseProduct'. " +
                    "Ensure the application database user can create tables and indexes.",
                exception,
            )
        }
    }

    private fun isSupportedDatabase(databaseProduct: String): Boolean =
        databaseProduct.contains("PostgreSQL", ignoreCase = true) ||
            databaseProduct.contains("H2", ignoreCase = true)

    private companion object {
        val statements =
            listOf(
                """
                CREATE TABLE IF NOT EXISTS auth_audit_events (
                  event_id VARCHAR(36) NOT NULL,
                  event_type VARCHAR(255) NOT NULL,
                  actor VARCHAR(255),
                  outcome VARCHAR(64) NOT NULL,
                  ip_address VARCHAR(255),
                  details_json TEXT NOT NULL,
                  event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
                  CONSTRAINT auth_audit_events_pk PRIMARY KEY (event_id)
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS auth_audit_events_timestamp_idx ON auth_audit_events (event_timestamp)",
                "CREATE INDEX IF NOT EXISTS auth_audit_events_actor_timestamp_idx ON auth_audit_events (actor, event_timestamp)",
                "CREATE INDEX IF NOT EXISTS auth_audit_events_type_timestamp_idx ON auth_audit_events (event_type, event_timestamp)",
                """
                CREATE TABLE IF NOT EXISTS snippets (
                  snippet_id VARCHAR(36) NOT NULL,
                  owner VARCHAR(255) NOT NULL,
                  group_id VARCHAR(255),
                  title VARCHAR(255) NOT NULL,
                  sql_text TEXT NOT NULL,
                  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  CONSTRAINT snippets_pk PRIMARY KEY (snippet_id)
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS snippets_owner_updated_idx ON snippets (owner, updated_at)",
                "CREATE INDEX IF NOT EXISTS snippets_group_updated_idx ON snippets (group_id, updated_at)",
                """
                CREATE TABLE IF NOT EXISTS resource_scripts (
                  resource_id VARCHAR(36) NOT NULL,
                  owner VARCHAR(255) NOT NULL,
                  title VARCHAR(255) NOT NULL,
                  sql_text TEXT NOT NULL,
                  scope VARCHAR(32) NOT NULL,
                  group_id VARCHAR(255),
                  folder_path VARCHAR(512) NOT NULL,
                  datasource_id VARCHAR(255),
                  tags_json TEXT NOT NULL,
                  allow_group_edit BOOLEAN NOT NULL,
                  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  current_revision INT NOT NULL,
                  CONSTRAINT resource_scripts_pk PRIMARY KEY (resource_id)
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS resource_scripts_owner_updated_idx ON resource_scripts (owner, updated_at)",
                "CREATE INDEX IF NOT EXISTS resource_scripts_group_updated_idx ON resource_scripts (group_id, updated_at)",
                """
                CREATE TABLE IF NOT EXISTS resource_script_versions (
                  version_id VARCHAR(36) NOT NULL,
                  resource_id VARCHAR(36) NOT NULL,
                  revision INT NOT NULL,
                  title VARCHAR(255) NOT NULL,
                  sql_text TEXT NOT NULL,
                  scope VARCHAR(32) NOT NULL,
                  group_id VARCHAR(255),
                  folder_path VARCHAR(512) NOT NULL,
                  datasource_id VARCHAR(255),
                  tags_json TEXT NOT NULL,
                  allow_group_edit BOOLEAN NOT NULL,
                  action VARCHAR(64) NOT NULL,
                  saved_by VARCHAR(255) NOT NULL,
                  saved_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  CONSTRAINT resource_script_versions_pk PRIMARY KEY (version_id)
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS resource_script_versions_resource_revision_idx ON resource_script_versions (resource_id, revision)",
                """
                CREATE TABLE IF NOT EXISTS app_users (
                  username VARCHAR(255) NOT NULL,
                  display_name VARCHAR(255) NOT NULL,
                  email VARCHAR(320),
                  password_hash TEXT,
                  provider VARCHAR(32) NOT NULL,
                  enabled BOOLEAN NOT NULL,
                  temporary_password BOOLEAN NOT NULL,
                  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  last_seen_at TIMESTAMP WITH TIME ZONE,
                  CONSTRAINT app_users_pk PRIMARY KEY (username)
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS app_user_roles (
                  username VARCHAR(255) NOT NULL,
                  role_name VARCHAR(128) NOT NULL,
                  CONSTRAINT app_user_roles_pk PRIMARY KEY (username, role_name)
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS app_user_groups (
                  username VARCHAR(255) NOT NULL,
                  group_id VARCHAR(255) NOT NULL,
                  CONSTRAINT app_user_groups_pk PRIMARY KEY (username, group_id)
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS rbac_groups (
                  group_id VARCHAR(255) NOT NULL,
                  group_name VARCHAR(255) NOT NULL,
                  description TEXT,
                  CONSTRAINT rbac_groups_pk PRIMARY KEY (group_id)
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS rbac_group_members (
                  group_id VARCHAR(255) NOT NULL,
                  username VARCHAR(255) NOT NULL,
                  CONSTRAINT rbac_group_members_pk PRIMARY KEY (group_id, username)
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS rbac_datasource_access (
                  group_id VARCHAR(255) NOT NULL,
                  datasource_id VARCHAR(255) NOT NULL,
                  can_query BOOLEAN NOT NULL,
                  can_export BOOLEAN NOT NULL,
                  read_only BOOLEAN NOT NULL,
                  max_rows_per_query INT,
                  max_runtime_seconds INT,
                  concurrency_limit INT,
                  credential_profile VARCHAR(255) NOT NULL,
                  CONSTRAINT rbac_datasource_access_pk PRIMARY KEY (group_id, datasource_id)
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS rbac_datasource_access_datasource_idx ON rbac_datasource_access (datasource_id)",
                """
                CREATE TABLE IF NOT EXISTS managed_datasources (
                  datasource_id VARCHAR(255) NOT NULL,
                  datasource_name VARCHAR(255) NOT NULL,
                  engine VARCHAR(64) NOT NULL,
                  host VARCHAR(512) NOT NULL,
                  port INT NOT NULL,
                  datasource_database VARCHAR(255),
                  driver_id VARCHAR(255) NOT NULL,
                  driver_class VARCHAR(512) NOT NULL,
                  pool_json TEXT NOT NULL,
                  tls_json TEXT NOT NULL,
                  options_json TEXT NOT NULL,
                  source VARCHAR(32) NOT NULL,
                  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  CONSTRAINT managed_datasources_pk PRIMARY KEY (datasource_id)
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS managed_credential_profiles (
                  datasource_id VARCHAR(255) NOT NULL,
                  profile_id VARCHAR(255) NOT NULL,
                  username VARCHAR(255) NOT NULL,
                  description TEXT,
                  sysadmin BOOLEAN NOT NULL,
                  encryption_key_id VARCHAR(255) NOT NULL,
                  encrypted_credential TEXT NOT NULL,
                  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  CONSTRAINT managed_credential_profiles_pk PRIMARY KEY (datasource_id, profile_id)
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS datasource_pause_state (
                  datasource_id VARCHAR(255) NOT NULL,
                  paused_by VARCHAR(255),
                  reason TEXT,
                  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  expires_at TIMESTAMP WITH TIME ZONE,
                  CONSTRAINT datasource_pause_state_pk PRIMARY KEY (datasource_id)
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS uploaded_jdbc_drivers (
                  driver_id VARCHAR(255) NOT NULL,
                  engine VARCHAR(64) NOT NULL,
                  driver_class VARCHAR(512) NOT NULL,
                  description TEXT NOT NULL,
                  jar_path TEXT NOT NULL,
                  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  CONSTRAINT uploaded_jdbc_drivers_pk PRIMARY KEY (driver_id)
                )
                """.trimIndent(),
            )
    }
}
