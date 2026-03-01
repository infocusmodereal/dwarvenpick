package com.dwarvenpick.app.session

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class JdbcSessionSchemaInitializer(
    private val jdbcTemplateProvider: ObjectProvider<JdbcTemplate>,
    private val environment: Environment,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(JdbcSessionSchemaInitializer::class.java)

    override fun run(args: ApplicationArguments) {
        val storeType = environment.getProperty("spring.session.store-type") ?: "none"
        if (!storeType.equals("jdbc", ignoreCase = true)) {
            return
        }

        val jdbcTemplate = jdbcTemplateProvider.ifAvailable ?: return
        val dataSource = jdbcTemplate.dataSource ?: return
        val databaseProduct =
            runCatching {
                dataSource.connection.use { connection -> connection.metaData.databaseProductName ?: "" }
            }.getOrDefault("")

        val statements =
            when {
                databaseProduct.contains("PostgreSQL", ignoreCase = true) -> postgresStatements
                databaseProduct.contains("H2", ignoreCase = true) -> h2Statements
                else -> {
                    logger.warn(
                        "JDBC session store is enabled but automatic schema init is not supported for database '{}'. " +
                            "Ensure the Spring Session tables exist (SPRING_SESSION, SPRING_SESSION_ATTRIBUTES).",
                        databaseProduct,
                    )
                    return
                }
            }

        try {
            statements.forEach { jdbcTemplate.execute(it) }
            logger.info("Spring Session schema ensured for database '{}'.", databaseProduct)
        } catch (exception: Exception) {
            throw IllegalStateException(
                "Failed to initialize Spring Session JDBC schema for database '$databaseProduct'. " +
                    "Ensure the database user can create tables and indexes.",
                exception,
            )
        }
    }

    private companion object {
        val postgresStatements: List<String> =
            listOf(
                """
                CREATE TABLE IF NOT EXISTS SPRING_SESSION (
                  PRIMARY_ID CHAR(36) NOT NULL,
                  SESSION_ID CHAR(36) NOT NULL,
                  CREATION_TIME BIGINT NOT NULL,
                  LAST_ACCESS_TIME BIGINT NOT NULL,
                  MAX_INACTIVE_INTERVAL INT NOT NULL,
                  EXPIRY_TIME BIGINT NOT NULL,
                  PRINCIPAL_NAME VARCHAR(100),
                  CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
                )
                """.trimIndent(),
                "CREATE UNIQUE INDEX IF NOT EXISTS SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID)",
                "CREATE INDEX IF NOT EXISTS SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME)",
                "CREATE INDEX IF NOT EXISTS SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME)",
                """
                CREATE TABLE IF NOT EXISTS SPRING_SESSION_ATTRIBUTES (
                  SESSION_PRIMARY_ID CHAR(36) NOT NULL,
                  ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
                  ATTRIBUTE_BYTES BYTEA NOT NULL,
                  CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
                  CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
                    REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
                )
                """.trimIndent(),
            )

        val h2Statements: List<String> =
            listOf(
                """
                CREATE TABLE IF NOT EXISTS SPRING_SESSION (
                  PRIMARY_ID CHAR(36) NOT NULL,
                  SESSION_ID CHAR(36) NOT NULL,
                  CREATION_TIME BIGINT NOT NULL,
                  LAST_ACCESS_TIME BIGINT NOT NULL,
                  MAX_INACTIVE_INTERVAL INT NOT NULL,
                  EXPIRY_TIME BIGINT NOT NULL,
                  PRINCIPAL_NAME VARCHAR(100),
                  CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
                )
                """.trimIndent(),
                "CREATE UNIQUE INDEX IF NOT EXISTS SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID)",
                "CREATE INDEX IF NOT EXISTS SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME)",
                "CREATE INDEX IF NOT EXISTS SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME)",
                """
                CREATE TABLE IF NOT EXISTS SPRING_SESSION_ATTRIBUTES (
                  SESSION_PRIMARY_ID CHAR(36) NOT NULL,
                  ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
                  ATTRIBUTE_BYTES BLOB NOT NULL,
                  CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
                  CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
                    REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
    }
}
