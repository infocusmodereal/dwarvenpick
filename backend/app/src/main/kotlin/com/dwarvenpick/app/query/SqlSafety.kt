package com.dwarvenpick.app.query

import java.util.Locale

/**
 * Small SQL safety helpers used for guardrails (read-only mode, validation).
 *
 * This is intentionally heuristic: it aims to block obvious writes while allowing common read-only patterns.
 */
object SqlSafety {
    fun isReadOnlySql(sql: String): Boolean {
        val normalizedSql = stripLeadingSqlComments(sql).trimStart().trimStart(';').trimStart()
        if (normalizedSql.isBlank()) {
            return true
        }

        val firstKeyword =
            Regex("^[a-zA-Z]+")
                .find(normalizedSql.lowercase(Locale.ROOT))
                ?.value
                ?: return true

        return when (firstKeyword) {
            "select", "show", "describe", "desc", "explain", "values" -> true
            "with" ->
                !Regex(
                    "\\b(insert|update|delete|merge|create|alter|drop|truncate|grant|revoke|call|copy|refresh|vacuum)\\b",
                    RegexOption.IGNORE_CASE,
                ).containsMatchIn(normalizedSql)

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
}
