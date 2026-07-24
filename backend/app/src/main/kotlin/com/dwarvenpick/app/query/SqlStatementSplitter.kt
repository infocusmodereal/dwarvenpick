package com.dwarvenpick.app.query

import com.dwarvenpick.app.datasource.DatasourceEngine

data class SqlStatementSegment(
    val sql: String,
    val start: Int,
    val end: Int,
)

/**
 * Very small SQL statement splitter.
 *
 * Goal:
 * - Support multi-statement scripting in a JDBC-safe way (execute statements one-by-one).
 * - Be good enough for common SQL: semicolon terminators, single/double quoted strings, line/block comments.
 *
 * This remains a bounded lexical splitter, not a full SQL parser. Dialect-specific inert regions are
 * delegated to [SqlLexicalScanner] so semicolons inside supported literals and comments are preserved.
 */
object SqlStatementSplitter {
    @Deprecated(
        message = "Pass DatasourceEngine so dialect-specific quoting cannot hide statement boundaries.",
        replaceWith = ReplaceWith("splitSqlStatements(sql, engine)"),
    )
    fun splitSqlStatements(sql: String): List<SqlStatementSegment> = splitSqlStatements(sql, engine = null)

    fun splitSqlStatements(
        sql: String,
        engine: DatasourceEngine?,
    ): List<SqlStatementSegment> {
        if (sql.isBlank()) {
            return emptyList()
        }

        val segments = mutableListOf<SqlStatementSegment>()
        var segmentStart = 0
        SqlLexicalScanner.analyze(sql, engine).statementTerminators.forEach { terminator ->
            trimSegment(sql, segmentStart, terminator)?.let { segments.add(it) }
            segmentStart = terminator + 1
        }

        trimSegment(sql, segmentStart, sql.length)?.let { segments.add(it) }
        return segments
    }

    private fun trimSegment(
        sql: String,
        startInclusive: Int,
        endExclusive: Int,
    ): SqlStatementSegment? {
        if (startInclusive >= endExclusive) {
            return null
        }

        val raw = sql.substring(startInclusive, endExclusive)
        if (raw.isBlank()) {
            return null
        }

        val leftTrimLength = raw.indexOfFirst { !it.isWhitespace() }.let { idx -> if (idx < 0) raw.length else idx }
        val rightTrimLength =
            raw
                .reversed()
                .indexOfFirst { !it.isWhitespace() }
                .let { idx -> if (idx < 0) raw.length else idx }

        val trimmedStart = startInclusive + leftTrimLength
        val trimmedEndExclusive = endExclusive - rightTrimLength
        if (trimmedStart >= trimmedEndExclusive) {
            return null
        }

        return SqlStatementSegment(
            sql = sql.substring(trimmedStart, trimmedEndExclusive),
            start = trimmedStart,
            end = trimmedEndExclusive,
        )
    }
}
