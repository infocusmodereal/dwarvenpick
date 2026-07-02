package com.dwarvenpick.app.inspector.providers

import com.dwarvenpick.app.inspector.OBJECT_INSPECTOR_CELL_CHAR_LIMIT
import com.dwarvenpick.app.inspector.OBJECT_INSPECTOR_TABLE_ROW_LIMIT
import com.dwarvenpick.app.inspector.ObjectInspectorKeyValue
import com.dwarvenpick.app.inspector.ObjectInspectorTable
import com.dwarvenpick.app.inspector.limitObjectInspectorText
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
    rowLimit: Int = OBJECT_INSPECTOR_TABLE_ROW_LIMIT,
    cellCharLimit: Int = OBJECT_INSPECTOR_CELL_CHAR_LIMIT,
): ObjectInspectorTable =
    connection.prepareStatement(sql).use { statement ->
        statement.queryTimeout = timeoutSeconds
        if (rowLimit > 0) {
            statement.maxRows = rowLimit + 1
        }
        bind(statement)
        statement.executeQuery().use { resultSet ->
            toInspectorTable(
                resultSet = resultSet,
                rowLimit = rowLimit,
                cellCharLimit = cellCharLimit,
            )
        }
    }

internal fun runKeyValueQuery(
    connection: Connection,
    sql: String,
    bind: (PreparedStatement) -> Unit = {},
    timeoutSeconds: Int = 10,
    valueCharLimit: Int = OBJECT_INSPECTOR_CELL_CHAR_LIMIT,
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
                            value =
                                resultSet.getString(index)?.let { value ->
                                    limitObjectInspectorText(value, valueCharLimit).value
                                },
                        ),
                    )
                }
            }
        }
    }

private fun toInspectorTable(
    resultSet: ResultSet,
    rowLimit: Int,
    cellCharLimit: Int,
): ObjectInspectorTable {
    val meta = resultSet.metaData
    val columns =
        (1..meta.columnCount).map { index ->
            meta.getColumnLabel(index)?.ifBlank { meta.getColumnName(index) } ?: "col$index"
        }

    val rows = mutableListOf<List<String?>>()
    var cellsTruncated = false
    var truncated = false
    while (resultSet.next()) {
        if (rowLimit > 0 && rows.size >= rowLimit) {
            truncated = true
            break
        }
        val row =
            (1..meta.columnCount).map { index ->
                resultSet.getString(index)?.let { value ->
                    val limited = limitObjectInspectorText(value, cellCharLimit)
                    if (limited.truncated) {
                        cellsTruncated = true
                    }
                    limited.value
                }
            }
        rows.add(row)
    }

    return ObjectInspectorTable(
        columns = columns,
        rows = rows,
        rowLimit = rowLimit.takeIf { it > 0 },
        truncated = truncated,
        cellLimit = cellCharLimit.takeIf { it > 0 },
        cellsTruncated = cellsTruncated,
    )
}
