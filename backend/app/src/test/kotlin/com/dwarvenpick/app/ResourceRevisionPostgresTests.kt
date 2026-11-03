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
    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var accounts: com.dwarvenpick.app.auth.UserAccountRepository

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var rbac: com.dwarvenpick.app.rbac.RbacService

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var transactions: org.springframework.transaction.PlatformTransactionManager

    @Test
    fun `group deletion cannot be undone by a concurrent user save`() {
        rbac.createGroup(
            com.dwarvenpick.app.rbac
                .CreateGroupRequest("delete-race"),
        )
        rbac.addMember("delete-race", "analyst")
        val accountLocked = java.util.concurrent.CountDownLatch(1)
        val releaseSave = java.util.concurrent.CountDownLatch(1)
        val deletionStarted = java.util.concurrent.CountDownLatch(1)
        java.util.concurrent.Executors.newFixedThreadPool(2).use { executor ->
            val save =
                executor.submit {
                    org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult {
                        val account = accounts.find("analyst")!!
                        accountLocked.countDown()
                        check(releaseSave.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        accounts.save(account)
                    }
                }
            check(accountLocked.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val deletion =
                executor.submit {
                    deletionStarted.countDown()
                    rbac.deleteGroup("delete-race")
                }
            try {
                check(deletionStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
                org.junit.jupiter.api.assertThrows<java.util.concurrent.TimeoutException> {
                    deletion.get(250, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            } finally {
                releaseSave.countDown()
            }
            save.get(10, java.util.concurrent.TimeUnit.SECONDS)
            deletion.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        org.assertj.core.api.Assertions
            .assertThat(accounts.find("analyst")!!.groups)
            .doesNotContain("delete-race")
    }

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
            jdbc.update(
                "INSERT INTO SPRING_SESSION (PRIMARY_ID,SESSION_ID,CREATION_TIME,LAST_ACCESS_TIME,MAX_INACTIVE_INTERVAL,EXPIRY_TIME) VALUES (?,?,0,0,28800,28800000)",
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002",
            )
            jdbc.update(
                "INSERT INTO SPRING_SESSION_ATTRIBUTES (SESSION_PRIMARY_ID,ATTRIBUTE_NAME,ATTRIBUTE_BYTES) VALUES (?,?,?)",
                "00000000-0000-0000-0000-000000000001",
                "SPRING_SECURITY_CONTEXT",
                byteArrayOf(1, 2, 3),
            )
            flyway("latest").migrate()
            org.assertj.core.api.Assertions
                .assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Int::class.java))
                .isZero()
            org.assertj.core.api.Assertions
                .assertThat(
                    jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES", Int::class.java),
                ).isZero()
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
