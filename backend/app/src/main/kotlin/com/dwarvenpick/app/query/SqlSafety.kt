package com.dwarvenpick.app.query

import java.util.Locale

/**
 * Small SQL safety helpers used for guardrails (read-only mode, validation).
 *
 * This is intentionally heuristic: it aims to block obvious writes while allowing common read-only patterns.
 */
object SqlSafety {
    private val firstKeywordRegex = Regex("^[a-zA-Z]+")
    private val writeKeywordRegex =
        Regex(
            "\\b(insert|update|delete|merge|create|alter|drop|truncate|grant|revoke|call|copy|refresh|vacuum)\\b",
            RegexOption.IGNORE_CASE,
        )
    private val explainPrefixRegex = Regex("^explain\\b", RegexOption.IGNORE_CASE)
    private val explainFormatRegex = Regex("^format\\s*=\\s*[a-zA-Z_][a-zA-Z0-9_]*\\b", RegexOption.IGNORE_CASE)
    private val nonExecutingExplainModifierRegex = Regex("^(verbose|extended|partitions)\\b", RegexOption.IGNORE_CASE)
    private val executingExplainModifierRegex = Regex("^analyze\\b", RegexOption.IGNORE_CASE)
    private val executingExplainOptionRegex = Regex("\\banalyze\\b", RegexOption.IGNORE_CASE)

    fun isReadOnlyScript(sql: String): Boolean =
        SqlStatementSplitter
            .splitSqlStatements(sql)
            .filter { segment -> hasExecutableSql(segment.sql) }
            .all { segment -> isReadOnlySql(segment.sql) }

    fun isSafeForValidation(sql: String): Boolean =
        SqlStatementSplitter
            .splitSqlStatements(sql)
            .filter { segment -> hasExecutableSql(segment.sql) }
            .all { segment -> isSafeStatementForValidation(segment.sql) }

    fun isReadOnlySql(sql: String): Boolean {
        val normalizedSql = normalizeStatementStart(sql)
        if (normalizedSql.isBlank()) {
            return true
        }

        val firstKeyword =
            firstKeywordRegex
                .find(normalizedSql.lowercase(Locale.ROOT))
                ?.value
                ?: return true

        return when (firstKeyword) {
            "select", "show", "describe", "desc", "values" -> true
            "explain" -> isReadOnlyExplainSql(normalizedSql)
            "with" ->
                !writeKeywordRegex.containsMatchIn(normalizedSql)

            else -> false
        }
    }

    fun stripLeadingSqlComments(sql: String): String {
        var working = sql.trimStart()
        while (working.isNotEmpty()) {
            when {
                working.startsWith("--") -> {
                    val nextLineIndex = working.indexOf('\n')
                    working =
                        if (nextLineIndex < 0) {
                            ""
                        } else {
                            working.substring(nextLineIndex + 1).trimStart()
                        }
                }

                working.startsWith("/*") -> {
                    val commentEndIndex = working.indexOf("*/")
                    working =
                        if (commentEndIndex < 0) {
                            ""
                        } else {
                            working.substring(commentEndIndex + 2).trimStart()
                        }
                }

                else -> return working
            }
        }
        return working
    }

    private fun hasExecutableSql(sql: String): Boolean = normalizeStatementStart(sql).isNotBlank()

    private fun normalizeStatementStart(sql: String): String =
        stripLeadingSqlComments(sql)
            .trimStart()
            .trimStart(';')
            .trimStart()

    private fun isSafeStatementForValidation(sql: String): Boolean {
        val normalizedSql = normalizeStatementStart(sql)
        if (normalizedSql.isBlank()) {
            return true
        }

        val firstKeyword =
            firstKeywordRegex
                .find(normalizedSql.lowercase(Locale.ROOT))
                ?.value
                ?: return true

        return firstKeyword != "explain" || isReadOnlyExplainSql(normalizedSql)
    }

    private fun isReadOnlyExplainSql(sql: String): Boolean {
        val explainedSql = unwrapNonExecutingExplain(sql) ?: return false
        return isReadOnlySql(explainedSql)
    }

    private fun unwrapNonExecutingExplain(sql: String): String? {
        var remaining = explainPrefixRegex.replaceFirst(sql, "").trimStart()
        if (remaining.isBlank()) {
            return null
        }

        while (remaining.isNotBlank()) {
            if (executingExplainModifierRegex.containsMatchIn(remaining)) {
                return null
            }

            if (remaining.startsWith("(")) {
                val optionEnd = findMatchingClosingParen(remaining) ?: return null
                val options = remaining.substring(1, optionEnd)
                if (writeKeywordRegex.containsMatchIn(options) || executingExplainOptionRegex.containsMatchIn(options)) {
                    return null
                }
                remaining = remaining.substring(optionEnd + 1).trimStart()
                continue
            }

            val explainFormatMatch = explainFormatRegex.find(remaining)
            if (explainFormatMatch?.range?.first == 0) {
                remaining = remaining.substring(explainFormatMatch.range.last + 1).trimStart()
                continue
            }

            val nonExecutingExplainModifierMatch = nonExecutingExplainModifierRegex.find(remaining)
            if (nonExecutingExplainModifierMatch?.range?.first == 0) {
                remaining = remaining.substring(nonExecutingExplainModifierMatch.range.last + 1).trimStart()
                continue
            }

            return remaining
        }

        return null
    }

    private fun findMatchingClosingParen(sql: String): Int? {
        var depth = 0
        sql.forEachIndexed { index, char ->
            when (char) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) {
                        return index
                    }
                }
            }
        }
        return null
    }
}
