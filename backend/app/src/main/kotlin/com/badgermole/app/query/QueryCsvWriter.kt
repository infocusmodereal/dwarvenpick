package com.badgermole.app.query

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object QueryCsvWriter {
    fun writeCsv(
        outputStream: OutputStream,
        columns: List<QueryResultColumn>,
        rows: List<List<String?>>,
        includeHeaders: Boolean,
    ) {
        val writer = BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))
        if (includeHeaders) {
            writer.write(formatRow(columns.map { column -> column.name }))
        }
        rows.forEach { row ->
            writer.write(formatRow(row))
        }
        writer.flush()
    }

    internal fun formatRow(values: List<String?>): String = values.joinToString(",") { value -> formatCell(value) } + "\n"

    private fun formatCell(value: String?): String {
        if (value == null) {
            return ""
        }

        val requiresQuotes =
            value.contains(",") ||
                value.contains("\"") ||
                value.contains("\n") ||
                value.contains("\r")

        if (!requiresQuotes) {
            return value
        }

        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
