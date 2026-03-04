package com.dwarvenpick.app.inspector.providers

import com.dwarvenpick.app.inspector.ObjectInspectorKeyValue
import com.dwarvenpick.app.inspector.ObjectInspectorTable
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

internal fun quoteIdentifier(
    quote: String,
    identifier: String,
): String = quote + identifier.replace(quote, quote + quote) + quote

internal fun buildQualifiedName(
    quote: String,
    parts: List<String>,
): String = parts.joinToString(".") { part -> quoteIdentifier(quote, part) }

internal fun runTableQuery(
    connection: Connection,
    sql: String,
    bind: (PreparedStatement) -> Unit = {},
    timeoutSeconds: Int = 10,
): ObjectInspectorTable =
    connection.prepareStatement(sql).use { statement ->
        statement.queryTimeout = timeoutSeconds
        bind(statement)
        statement.executeQuery().use { resultSet ->
            toInspectorTable(resultSet)
        }
    }

internal fun runKeyValueQuery(
    connection: Connection,
    sql: String,
    bind: (PreparedStatement) -> Unit = {},
    timeoutSeconds: Int = 10,
): List<ObjectInspectorKeyValue> =
    connection.prepareStatement(sql).use { statement ->
        statement.queryTimeout = timeoutSeconds
        bind(statement)
        statement.executeQuery().use { resultSet ->
            val meta = resultSet.metaData
            if (!resultSet.next()) {
                return emptyList()
            }

            buildList {
                (1..meta.columnCount).forEach { index ->
                    val key =
                        meta.getColumnLabel(index)?.ifBlank { meta.getColumnName(index) }
                            ?: "col$index"
                    add(
                        ObjectInspectorKeyValue(
                            key = key,
                            value = resultSet.getString(index),
                        ),
                    )
                }
            }
        }
    }

private fun toInspectorTable(resultSet: ResultSet): ObjectInspectorTable {
    val meta = resultSet.metaData
    val columns =
        (1..meta.columnCount).map { index ->
            meta.getColumnLabel(index)?.ifBlank { meta.getColumnName(index) } ?: "col$index"
        }

    val rows = mutableListOf<List<String?>>()
    while (resultSet.next()) {
        val row = (1..meta.columnCount).map { index -> resultSet.getString(index) }
        rows.add(row)
    }

    return ObjectInspectorTable(
        columns = columns,
        rows = rows,
    )
}
