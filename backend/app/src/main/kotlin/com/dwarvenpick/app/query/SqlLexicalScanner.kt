package com.dwarvenpick.app.query

import com.dwarvenpick.app.datasource.DatasourceEngine

internal data class SqlLexicalAnalysis(
    val maskedSql: String,
    val statementTerminators: List<Int>,
    val valid: Boolean,
)

private enum class DollarQuoteMode {
    NONE,
    UNTAGGED,
    TAGGED,
}

private data class SqlDialectProfile(
    val backtickIdentifiers: Boolean = false,
    val hashLineComments: Boolean = false,
    val dashCommentRequiresWhitespace: Boolean = false,
    val nestedBlockComments: Boolean = false,
    val dollarQuoteMode: DollarQuoteMode = DollarQuoteMode.NONE,
    val backslashEscapes: Boolean = false,
    val postgresEscapeStrings: Boolean = false,
    val blockExecutableComments: Boolean = true,
)

/**
 * Masks dialect-recognized literals, quoted identifiers, and comments while preserving source offsets.
 *
 * The scanner intentionally recognizes only syntax confirmed for the selected engine. Treating an
 * unsupported construct as inert could hide a write from the read-only guard.
 */
internal object SqlLexicalScanner {
    fun analyze(
        sql: String,
        engine: DatasourceEngine? = null,
    ): SqlLexicalAnalysis {
        val profile = profileFor(engine)
        val masked = sql.toCharArray()
        val terminators = mutableListOf<Int>()
        var valid = true
        var parenthesisDepth = 0
        var index = 0

        while (index < sql.length) {
            val current = sql[index]
            val next = sql.getOrNull(index + 1)

            if (current == '-' && next == '-' && isDashCommentStart(sql, index, profile)) {
                val end = sql.indexOf('\n', startIndex = index + 2).let { if (it < 0) sql.length else it }
                mask(masked, index, end)
                index = end
                continue
            }

            if (current == '#' && profile.hashLineComments) {
                val end = sql.indexOf('\n', startIndex = index + 1).let { if (it < 0) sql.length else it }
                mask(masked, index, end)
                index = end
                continue
            }

            if (current == '/' && next == '*') {
                val executableComment =
                    sql.startsWith("/*!", index) ||
                        sql.startsWith("/*M!", index, ignoreCase = true)
                if (executableComment && profile.blockExecutableComments) {
                    valid = false
                }
                val end = findBlockCommentEnd(sql, index, profile.nestedBlockComments)
                if (end == null) {
                    mask(masked, index, sql.length)
                    valid = false
                    break
                }
                mask(masked, index, end)
                index = end
                continue
            }

            val dollarDelimiter = dollarQuoteDelimiterAt(sql, index, profile)
            if (dollarDelimiter != null) {
                val closeIndex = sql.indexOf(dollarDelimiter, startIndex = index + dollarDelimiter.length)
                if (closeIndex < 0) {
                    mask(masked, index, sql.length)
                    valid = false
                    break
                }
                val end = closeIndex + dollarDelimiter.length
                mask(masked, index, end)
                index = end
                continue
            }

            if (current == '\'' || current == '"' || (current == '`' && profile.backtickIdentifiers)) {
                val backslashEscapes =
                    profile.backslashEscapes ||
                        (
                            current == '\'' &&
                                profile.postgresEscapeStrings &&
                                hasPostgresEscapeStringPrefix(sql, index)
                        )
                val end = findQuotedEnd(sql, index, current, backslashEscapes)
                if (end == null) {
                    mask(masked, index, sql.length)
                    valid = false
                    break
                }
                mask(masked, index, end)
                index = end
                continue
            }

            when (current) {
                '(' -> parenthesisDepth += 1
                ')' -> {
                    parenthesisDepth -= 1
                    if (parenthesisDepth < 0) {
                        valid = false
                        parenthesisDepth = 0
                    }
                }

                ';' -> terminators += index
            }
            index += 1
        }

        if (parenthesisDepth != 0) {
            valid = false
        }

        return SqlLexicalAnalysis(
            maskedSql = String(masked),
            statementTerminators = terminators,
            valid = valid,
        )
    }

    private fun profileFor(engine: DatasourceEngine?): SqlDialectProfile =
        when (engine) {
            DatasourceEngine.POSTGRESQL ->
                SqlDialectProfile(
                    nestedBlockComments = true,
                    dollarQuoteMode = DollarQuoteMode.TAGGED,
                    postgresEscapeStrings = true,
                )

            DatasourceEngine.MYSQL, DatasourceEngine.MARIADB ->
                SqlDialectProfile(
                    backtickIdentifiers = true,
                    hashLineComments = true,
                    dashCommentRequiresWhitespace = true,
                    backslashEscapes = true,
                )

            DatasourceEngine.STARROCKS ->
                SqlDialectProfile(
                    backtickIdentifiers = true,
                )

            DatasourceEngine.VERTICA ->
                SqlDialectProfile(
                    nestedBlockComments = true,
                    dollarQuoteMode = DollarQuoteMode.UNTAGGED,
                )

            DatasourceEngine.TRINO, DatasourceEngine.AEROSPIKE, null -> SqlDialectProfile()
        }

    private fun isDashCommentStart(
        sql: String,
        index: Int,
        profile: SqlDialectProfile,
    ): Boolean {
        if (!profile.dashCommentRequiresWhitespace) {
            return true
        }
        val following = sql.getOrNull(index + 2) ?: return true
        return following.isWhitespace() || following.isISOControl()
    }

    private fun findBlockCommentEnd(
        sql: String,
        start: Int,
        nested: Boolean,
    ): Int? {
        var depth = 1
        var index = start + 2
        while (index < sql.length - 1) {
            when {
                nested && sql[index] == '/' && sql[index + 1] == '*' -> {
                    depth += 1
                    index += 2
                }

                sql[index] == '*' && sql[index + 1] == '/' -> {
                    depth -= 1
                    index += 2
                    if (depth == 0) {
                        return index
                    }
                }

                else -> index += 1
            }
        }
        return null
    }

    private fun dollarQuoteDelimiterAt(
        sql: String,
        index: Int,
        profile: SqlDialectProfile,
    ): String? {
        if (sql[index] != '$' || profile.dollarQuoteMode == DollarQuoteMode.NONE) {
            return null
        }
        val previous = sql.getOrNull(index - 1)
        if (previous != null && (previous.isLetterOrDigit() || previous == '_' || previous == '$')) {
            return null
        }
        if (sql.startsWith("$$", index)) {
            return "$$"
        }
        if (profile.dollarQuoteMode != DollarQuoteMode.TAGGED) {
            return null
        }

        var cursor = index + 1
        val first = sql.getOrNull(cursor) ?: return null
        if (!(first.isLetter() || first == '_')) {
            return null
        }
        cursor += 1
        while (cursor < sql.length && (sql[cursor].isLetterOrDigit() || sql[cursor] == '_')) {
            cursor += 1
        }
        if (sql.getOrNull(cursor) != '$') {
            return null
        }
        return sql.substring(index, cursor + 1)
    }

    private fun findQuotedEnd(
        sql: String,
        start: Int,
        quote: Char,
        backslashEscapes: Boolean,
    ): Int? {
        var index = start + 1
        while (index < sql.length) {
            val current = sql[index]
            val next = sql.getOrNull(index + 1)
            if (backslashEscapes && current == '\\' && next != null) {
                index += 2
                continue
            }
            if (current == quote && next == quote) {
                index += 2
                continue
            }
            if (current == quote) {
                return index + 1
            }
            index += 1
        }
        return null
    }

    private fun hasPostgresEscapeStringPrefix(
        sql: String,
        quoteIndex: Int,
    ): Boolean {
        val prefix = sql.getOrNull(quoteIndex - 1) ?: return false
        if (prefix != 'e' && prefix != 'E') {
            return false
        }
        val beforePrefix = sql.getOrNull(quoteIndex - 2)
        return beforePrefix == null || !(beforePrefix.isLetterOrDigit() || beforePrefix == '_' || beforePrefix == '$')
    }

    private fun mask(
        chars: CharArray,
        startInclusive: Int,
        endExclusive: Int,
    ) {
        for (index in startInclusive until endExclusive.coerceAtMost(chars.size)) {
            if (chars[index] != '\n' && chars[index] != '\r') {
                chars[index] = ' '
            }
        }
    }
}
