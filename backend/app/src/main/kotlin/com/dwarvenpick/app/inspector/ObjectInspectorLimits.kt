package com.dwarvenpick.app.inspector

internal const val OBJECT_INSPECTOR_TABLE_ROW_LIMIT = 200
internal const val OBJECT_INSPECTOR_TEXT_CHAR_LIMIT = 65_536
internal const val OBJECT_INSPECTOR_CELL_CHAR_LIMIT = 8_192

private const val TRUNCATION_MARKER_PREFIX = "\n\n[truncated to "
private const val TRUNCATION_MARKER_SUFFIX = " characters]"

internal data class ObjectInspectorLimitedText(
    val value: String,
    val truncated: Boolean,
)

internal fun limitObjectInspectorText(
    value: String,
    charLimit: Int,
): ObjectInspectorLimitedText {
    if (charLimit <= 0 || value.length <= charLimit) {
        return ObjectInspectorLimitedText(value = value, truncated = false)
    }

    return ObjectInspectorLimitedText(
        value = value.take(charLimit) + TRUNCATION_MARKER_PREFIX + charLimit + TRUNCATION_MARKER_SUFFIX,
        truncated = true,
    )
}

internal fun limitObjectInspectorTable(
    table: ObjectInspectorTable,
    rowLimit: Int = OBJECT_INSPECTOR_TABLE_ROW_LIMIT,
    cellCharLimit: Int = OBJECT_INSPECTOR_CELL_CHAR_LIMIT,
): ObjectInspectorTable {
    var cellsTruncated = table.cellsTruncated
    val limitedRows =
        table.rows
            .take(rowLimit)
            .map { row ->
                row.map { cell ->
                    cell?.let { value ->
                        val limited = limitObjectInspectorText(value, cellCharLimit)
                        if (limited.truncated) {
                            cellsTruncated = true
                        }
                        limited.value
                    }
                }
            }

    return table.copy(
        rows = limitedRows,
        rowLimit = rowLimit,
        truncated = table.truncated || table.rows.size > rowLimit,
        cellLimit = cellCharLimit,
        cellsTruncated = cellsTruncated,
    )
}
