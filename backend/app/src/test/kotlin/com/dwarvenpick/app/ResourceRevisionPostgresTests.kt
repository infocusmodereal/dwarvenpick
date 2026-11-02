package com.dwarvenpick.app

import org.junit.jupiter.api.Test
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ResourceRevisionPostgresTests : ResourceRevisionIntegrityTests() {
    @Test
    fun `upgrade preserves existing identity and canonical memberships`() {
        val schema = "upgrade_integrity"

        fun flyway(target: String) =
            org.flywaydb.core.Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
                .defaultSchema(schema)
                .target(target)
                .load()
        flyway("5").migrate()
        java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            val jdbc =
                org.springframework.jdbc.core.JdbcTemplate(
                    org.springframework.jdbc.datasource
                        .SingleConnectionDataSource(connection, true),
                )
            jdbc.update(
                """
                INSERT INTO app_users (username,display_name,provider,enabled,temporary_password,created_at,updated_at)
                VALUES ('existing','Existing','OIDC',TRUE,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """.trimIndent(),
            )
            jdbc.update("INSERT INTO rbac_groups (group_id,group_name) VALUES ('readers','readers')")
            jdbc.update("INSERT INTO app_user_groups (username,group_id) VALUES ('existing','readers')")
            jdbc.update("INSERT INTO rbac_group_members (username,group_id) VALUES ('existing','stale-grant')")
            flyway("latest").migrate()
            org.assertj.core.api.Assertions
                .assertThat(
                    jdbc.queryForObject(
                        "SELECT user_id FROM app_users WHERE username='existing'",
                        String::class.java,
                    ),
                ).isEqualTo("legacy:existing")
            org.assertj.core.api.Assertions
                .assertThat(
                    jdbc.queryForList(
                        "SELECT group_id FROM app_user_groups WHERE username='existing'",
                        String::class.java,
                    ),
                ).containsExactly("readers")
            org.junit.jupiter.api.assertThrows<org.springframework.dao.DataIntegrityViolationException> {
                jdbc.update("INSERT INTO app_user_roles (username,role_name) VALUES ('missing','USER')")
            }
        }
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:15-alpine")

        @DynamicPropertySource @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
