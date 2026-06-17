package com.dwarvenpick.app.query

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class QueryHistorySchemaInitializer(
    private val jdbcTemplateProvider: ObjectProvider<JdbcTemplate>,
) : SmartInitializingSingleton {
    private val logger = LoggerFactory.getLogger(QueryHistorySchemaInitializer::class.java)

    override fun afterSingletonsInstantiated() {
        val jdbcTemplate = jdbcTemplateProvider.ifAvailable ?: return
        val dataSource = jdbcTemplate.dataSource ?: return
        val databaseProduct =
            runCatching {
                dataSource.connection.use { connection -> connection.metaData.databaseProductName ?: "" }
            }.getOrDefault("")

        if (!isSupportedDatabase(databaseProduct)) {
            logger.warn(
                "Query history persistence schema init is not supported for database '{}'. " +
                    "Ensure the query_history table exists before using persistent query history.",
                databaseProduct,
            )
            return
        }

        try {
            statements.forEach { jdbcTemplate.execute(it) }
            logger.info("Query history schema ensured for database '{}'.", databaseProduct)
        } catch (exception: Exception) {
            throw IllegalStateException(
                "Failed to initialize query history schema for database '$databaseProduct'. " +
                    "Ensure the application database user can create tables and indexes.",
                exception,
            )
        }
    }

    private fun isSupportedDatabase(databaseProduct: String): Boolean =
        databaseProduct.contains("PostgreSQL", ignoreCase = true) ||
            databaseProduct.contains("H2", ignoreCase = true)

    private companion object {
        val statements: List<String> =
            listOf(
                """
                CREATE TABLE IF NOT EXISTS query_history (
                  execution_id VARCHAR(36) NOT NULL,
                  actor VARCHAR(255) NOT NULL,
                  datasource_id VARCHAR(255) NOT NULL,
                  credential_profile VARCHAR(255) NOT NULL,
                  query_hash VARCHAR(128) NOT NULL,
                  query_text TEXT,
                  query_text_redacted BOOLEAN NOT NULL DEFAULT FALSE,
                  status VARCHAR(32) NOT NULL,
                  message TEXT NOT NULL,
                  error_summary TEXT,
                  row_count INT NOT NULL,
                  column_count INT NOT NULL,
                  row_limit_reached BOOLEAN NOT NULL,
                  max_rows_per_query INT NOT NULL,
                  max_runtime_seconds INT NOT NULL,
                  submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  started_at TIMESTAMP WITH TIME ZONE,
                  completed_at TIMESTAMP WITH TIME ZONE,
                  CONSTRAINT query_history_pk PRIMARY KEY (execution_id)
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS query_history_actor_submitted_idx ON query_history (actor, submitted_at)",
                "CREATE INDEX IF NOT EXISTS query_history_datasource_submitted_idx ON query_history (datasource_id, submitted_at)",
                "CREATE INDEX IF NOT EXISTS query_history_status_submitted_idx ON query_history (status, submitted_at)",
                "CREATE INDEX IF NOT EXISTS query_history_completed_submitted_idx ON query_history (completed_at, submitted_at)",
            )
    }
}
