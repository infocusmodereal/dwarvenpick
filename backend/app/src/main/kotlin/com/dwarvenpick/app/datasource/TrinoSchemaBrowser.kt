package com.dwarvenpick.app.datasource

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant

/** Catalog-scoped metadata reads; no parallel fan-out to the coordinator or connectors. */
internal class TrinoSchemaBrowser(
    private val properties: SchemaBrowserProperties,
) {
    fun fetch(
        connection: Connection,
        catalog: String,
        datasourceId: String,
        fetchedAt: Instant,
        load: SchemaBrowserLoad,
    ): DatasourceSchemaBrowserResponse {
        val informationSchema = "\"${catalog.replace("\"", "\"\"")}\".information_schema"
        val schemas =
            query(
                connection,
                "SELECT schema_name FROM $informationSchema.schemata ORDER BY schema_name LIMIT ${properties.maxSchemas.coerceAtLeast(1)}",
                load,
            ) { result -> result.getString("schema_name") }

        val entries =
            schemas.map { schema ->
                val tables =
                    query(
                        connection,
                        """
                        SELECT table_name, table_type FROM $informationSchema.tables
                        WHERE table_schema = ? AND table_type IN ('BASE TABLE', 'VIEW')
                        ORDER BY table_type, table_name LIMIT ${properties.maxTablesPerSchema.coerceAtLeast(1)}
                        """.trimIndent(),
                        load,
                        bind = { setString(1, schema) },
                    ) { result ->
                        DatasourceTableEntryResponse(
                            table = result.getString("table_name"),
                            type = if (result.getString("table_type") == "VIEW") "VIEW" else "TABLE",
                            columns = emptyList(),
                        )
                    }
                val columnsByTable = mutableMapOf<String, MutableList<DatasourceColumnEntryResponse>>()
                // Keep exact table predicates small. Broad getColumns("%") or a multi-name
                // filter on system.jdbc.columns can enumerate an entire connector schema.
                tables.chunked(COLUMN_BATCH_SIZE).forEach { batch ->
                    val placeholders = batch.joinToString(", ") { "?" }
                    readRows(
                        connection,
                        """
                        SELECT table_name, column_name, data_type, is_nullable
                        FROM $informationSchema.columns
                        WHERE table_schema = ? AND table_name IN ($placeholders)
                          AND ordinal_position <= ${properties.maxColumnsPerTable.coerceAtLeast(1)}
                        ORDER BY table_name, ordinal_position
                        """.trimIndent(),
                        load,
                        bind = {
                            setString(1, schema)
                            batch.forEachIndexed { index, table -> setString(index + 2, table.table) }
                        },
                    ) { result ->
                        columnsByTable.getOrPut(result.getString("table_name")) { mutableListOf() }.add(
                            DatasourceColumnEntryResponse(
                                name = result.getString("column_name"),
                                jdbcType = result.getString("data_type"),
                                nullable = result.getString("is_nullable") != "NO",
                            ),
                        )
                    }
                }
                DatasourceSchemaEntryResponse(
                    schema = schema,
                    tables = tables.map { it.copy(columns = columnsByTable[it.table].orEmpty()) }.sortedBy { it.table },
                )
            }
        return DatasourceSchemaBrowserResponse(datasourceId, false, fetchedAt.toString(), entries)
    }

    private fun <T> query(
        connection: Connection,
        sql: String,
        load: SchemaBrowserLoad,
        bind: PreparedStatement.() -> Unit = {},
        row: (ResultSet) -> T,
    ): List<T> =
        buildList {
            readRows(connection, sql, load, bind) { add(row(it)) }
        }

    private fun readRows(
        connection: Connection,
        sql: String,
        load: SchemaBrowserLoad,
        bind: PreparedStatement.() -> Unit = {},
        row: (ResultSet) -> Unit,
    ) {
        load.checkActive()
        connection.prepareStatement(sql).use { statement ->
            statement.queryTimeout = properties.maxLoadSeconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            statement.bind()
            statement.executeQuery().use { result ->
                while (result.next()) {
                    load.checkActive()
                    row(result)
                }
            }
        }
    }

    companion object {
        // Trino 479 defaults to 100 prefetched information_schema prefixes.
        // Stay below that threshold and restrict each batch to one schema.
        internal const val COLUMN_BATCH_SIZE = 32
    }
}
