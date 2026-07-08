package com.dwarvenpick.app.query

import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.starrocks.parseStarRocksExplorerSchema
import com.dwarvenpick.app.starrocks.qualifiedSchemaName
import java.sql.Connection

private const val MAX_DEFAULT_SCHEMA_LENGTH = 256

internal class AppliedQueryDefaultSchema(
    evictConnectionOnClose: Boolean,
    private val restore: () -> Unit = {},
) : AutoCloseable {
    var evictConnectionOnClose: Boolean = evictConnectionOnClose
        private set

    override fun close() {
        runCatching { restore() }
            .onFailure {
                evictConnectionOnClose = true
            }
    }
}

internal object QueryDefaultSchema {
    fun normalize(defaultSchema: String?): String? {
        val normalized = defaultSchema?.trim()?.takeIf { value -> value.isNotBlank() } ?: return null
        require(normalized.length <= MAX_DEFAULT_SCHEMA_LENGTH) {
            "Default schema must be $MAX_DEFAULT_SCHEMA_LENGTH characters or fewer."
        }
        require(normalized.none { character -> character.isISOControl() }) {
            "Default schema cannot contain control characters."
        }
        return normalized
    }

    fun apply(
        connection: Connection,
        engine: DatasourceEngine,
        defaultSchema: String?,
    ): AppliedQueryDefaultSchema {
        val normalized = normalize(defaultSchema) ?: return AppliedQueryDefaultSchema(evictConnectionOnClose = false)

        return when (engine) {
            DatasourceEngine.MYSQL,
            DatasourceEngine.MARIADB,
            -> applyUseSchema(connection, quoteBacktickIdentifier(normalized))

            DatasourceEngine.STARROCKS ->
                applyUseSchema(
                    connection,
                    parseStarRocksExplorerSchema(normalized).qualifiedSchemaName(includeDefaultCatalog = true),
                )

            DatasourceEngine.POSTGRESQL,
            DatasourceEngine.TRINO,
            DatasourceEngine.VERTICA,
            -> applyJdbcSchema(connection, normalized)

            DatasourceEngine.AEROSPIKE -> AppliedQueryDefaultSchema(evictConnectionOnClose = false)
        }
    }

    private fun applyUseSchema(
        connection: Connection,
        quotedSchema: String,
    ): AppliedQueryDefaultSchema {
        connection.createStatement().use { statement ->
            statement.execute("USE $quotedSchema")
        }
        return AppliedQueryDefaultSchema(evictConnectionOnClose = true)
    }

    private fun applyJdbcSchema(
        connection: Connection,
        schema: String,
    ): AppliedQueryDefaultSchema {
        val originalSchema = runCatching { connection.schema }.getOrNull()
        connection.schema = schema
        return AppliedQueryDefaultSchema(evictConnectionOnClose = false) {
            connection.schema = originalSchema
        }
    }

    private fun quoteBacktickIdentifier(identifier: String): String = "`" + identifier.replace("`", "``") + "`"
}
