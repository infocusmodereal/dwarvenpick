package com.dwarvenpick.app.query

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
 * Non-goals:
 * - Full SQL parsing (dollar-quoting, vendor-specific delimiters, etc.).
 */
object SqlStatementSplitter {
    fun splitSqlStatements(sql: String): List<SqlStatementSegment> {
        if (sql.isBlank()) {
            return emptyList()
        }

        val segments = mutableListOf<SqlStatementSegment>()
        var segmentStart = 0

        var inSingleQuote = false
        var inDoubleQuote = false
        var inLineComment = false
        var inBlockComment = false

        var index = 0
        while (index < sql.length) {
            val current = sql[index]
            val next = if (index + 1 < sql.length) sql[index + 1] else null

            if (inLineComment) {
                if (current == '\n') {
                    inLineComment = false
                }
                index += 1
                continue
            }

            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    inBlockComment = false
                    index += 2
                    continue
                }
                index += 1
                continue
            }

            if (inSingleQuote) {
                if (current == '\'' && next == '\'') {
                    index += 2
                    continue
                }
                if (current == '\'') {
                    inSingleQuote = false
                }
                index += 1
                continue
            }

            if (inDoubleQuote) {
                if (current == '"' && next == '"') {
                    index += 2
                    continue
                }
                if (current == '"') {
                    inDoubleQuote = false
                }
                index += 1
                continue
            }

            if (current == '-' && next == '-') {
                inLineComment = true
                index += 2
                continue
            }

            if (current == '/' && next == '*') {
                inBlockComment = true
                index += 2
                continue
            }

            if (current == '\'') {
                inSingleQuote = true
                index += 1
                continue
            }

            if (current == '"') {
                inDoubleQuote = true
                index += 1
                continue
            }

            if (current == ';') {
                trimSegment(sql, segmentStart, index)?.let { segments.add(it) }
                segmentStart = index + 1
            }

            index += 1
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
