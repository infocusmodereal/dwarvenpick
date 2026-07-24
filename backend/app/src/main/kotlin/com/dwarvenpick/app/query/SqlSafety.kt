package com.dwarvenpick.app.query

import com.dwarvenpick.app.datasource.DatasourceEngine
import java.util.Locale

/**
 * Conservative lexical SQL guardrails for read-only mappings and validation.
 *
 * This is not a semantic SQL sandbox. Database-enforced read-only credentials remain the final
 * authorization boundary, especially for side-effecting functions and engine extensions.
 */
object SqlSafety {
    private val wordRegex = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
    private val blockedWords =
        setOf(
            "insert",
            "update",
            "delete",
            "merge",
            "upsert",
            "replace",
            "create",
            "alter",
            "drop",
            "truncate",
            "grant",
            "revoke",
            "call",
            "copy",
            "refresh",
            "vacuum",
            "load",
            "set",
            "prepare",
            "execute",
            "deallocate",
            "comment",
            "import",
            "export",
            "install",
            "remove",
            "attach",
            "detach",
        )
    private val explainModifiers = setOf("verbose", "extended", "partitions", "format", "json", "text", "tree")

    @Deprecated(
        message = "Pass DatasourceEngine so dialect-specific quoting cannot hide writes.",
        replaceWith = ReplaceWith("isReadOnlyScript(sql, engine)"),
    )
    fun isReadOnlyScript(sql: String): Boolean = isReadOnlyScript(sql, engine = null)

    fun isReadOnlyScript(
        sql: String,
        engine: DatasourceEngine?,
    ): Boolean {
        val analysis = SqlLexicalScanner.analyze(sql, engine)
        if (!analysis.valid) {
            return false
        }
        return SqlStatementSplitter
            .splitSqlStatements(sql, engine)
            .filter { segment -> hasExecutableSql(segment.sql, engine) }
            .all { segment -> isReadOnlySql(segment.sql, engine) }
    }

    @Deprecated(
        message = "Pass DatasourceEngine so dialect-specific quoting cannot hide validation behavior.",
        replaceWith = ReplaceWith("isSafeForValidation(sql, engine)"),
    )
    fun isSafeForValidation(sql: String): Boolean = isSafeForValidation(sql, engine = null)

    fun isSafeForValidation(
        sql: String,
        engine: DatasourceEngine?,
    ): Boolean {
        val analysis = SqlLexicalScanner.analyze(sql, engine)
        if (!analysis.valid) {
            return false
        }
        return SqlStatementSplitter
            .splitSqlStatements(sql, engine)
            .filter { segment -> hasExecutableSql(segment.sql, engine) }
            .all { segment -> isSafeStatementForValidation(segment.sql, engine) }
    }

    @Deprecated(
        message = "Pass DatasourceEngine so dialect-specific quoting cannot hide writes.",
        replaceWith = ReplaceWith("isReadOnlySql(sql, engine)"),
    )
    fun isReadOnlySql(sql: String): Boolean = isReadOnlySql(sql, engine = null)

    fun isReadOnlySql(
        sql: String,
        engine: DatasourceEngine?,
    ): Boolean {
        val analysis = SqlLexicalScanner.analyze(sql, engine)
        if (!analysis.valid) {
            return false
        }

        val executableSegments =
            SqlStatementSplitter
                .splitSqlStatements(sql, engine)
                .filter { segment -> hasExecutableSql(segment.sql, engine) }
        if (executableSegments.size > 1) {
            return false
        }
        if (executableSegments.isEmpty()) {
            return true
        }

        val maskedSql = SqlLexicalScanner.analyze(executableSegments.single().sql, engine).maskedSql
        return classifyMaskedSql(maskedSql, engine)
    }

    fun hasExecutableSql(
        sql: String,
        engine: DatasourceEngine?,
    ): Boolean {
        val analysis = SqlLexicalScanner.analyze(sql, engine)
        return !analysis.valid || tokens(analysis.maskedSql).isNotEmpty()
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

                working.startsWith("/*") &&
                    !working.startsWith("/*!") &&
                    !working.startsWith("/*M!", ignoreCase = true) -> {
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

    private fun classifyMaskedSql(
        maskedSql: String,
        engine: DatasourceEngine?,
    ): Boolean {
        val sqlTokens = tokens(maskedSql)
        val first = firstMeaningfulToken(maskedSql, sqlTokens) ?: return maskedSql.isBlank()

        return when (first.value) {
            "select" -> isReadOnlySelect(sqlTokens, first)
            "show", "describe", "desc", "values" -> true
            "table" ->
                engine in
                    setOf(
                        DatasourceEngine.POSTGRESQL,
                        DatasourceEngine.MYSQL,
                        DatasourceEngine.MARIADB,
                        DatasourceEngine.TRINO,
                    )
            "explain" -> isReadOnlyExplain(maskedSql, sqlTokens, first, engine)
            "with" -> isReadOnlyWith(maskedSql, sqlTokens, first, engine)
            else -> false
        }
    }

    private fun isReadOnlySelect(
        sqlTokens: List<SqlWordToken>,
        selectToken: SqlWordToken,
    ): Boolean =
        sqlTokens
            .asSequence()
            .dropWhile { token -> token.start <= selectToken.start }
            .none { token -> token.depth == selectToken.depth && token.value == "into" }

    private fun isReadOnlyWith(
        maskedSql: String,
        sqlTokens: List<SqlWordToken>,
        withToken: SqlWordToken,
        engine: DatasourceEngine?,
    ): Boolean {
        var cursor = withToken.end
        while (cursor < maskedSql.length) {
            val asToken =
                sqlTokens.firstOrNull { token ->
                    token.start >= cursor &&
                        token.depth == withToken.depth &&
                        token.value == "as"
                } ?: return false
            val body = cteBodyAfter(maskedSql, asToken) ?: return false
            if (!classifyMaskedSql(body.sql, engine)) {
                return false
            }

            cursor = skipWhitespace(maskedSql, body.endExclusive)
            if (maskedSql.getOrNull(cursor) == ',') {
                cursor += 1
                continue
            }
            return classifyMaskedSql(maskedSql.substring(cursor), engine)
        }
        return false
    }

    private fun cteBodyAfter(
        maskedSql: String,
        asToken: SqlWordToken,
    ): CteBody? {
        val openingParenthesis = maskedSql.indexOf('(', startIndex = asToken.end)
        if (openingParenthesis < 0) {
            return null
        }

        val between =
            maskedSql
                .substring(asToken.end, openingParenthesis)
                .trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")
        if (between.isNotEmpty() && between !in setOf("materialized", "not materialized")) {
            return null
        }

        var depth = 1
        var index = openingParenthesis + 1
        while (index < maskedSql.length) {
            when (maskedSql[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) {
                        return CteBody(
                            sql = maskedSql.substring(openingParenthesis + 1, index),
                            endExclusive = index + 1,
                        )
                    }
                }
            }
            index += 1
        }
        return null
    }

    private fun skipWhitespace(
        value: String,
        start: Int,
    ): Int {
        var index = start
        while (index < value.length && value[index].isWhitespace()) {
            index += 1
        }
        return index
    }

    private fun isReadOnlyExplain(
        maskedSql: String,
        sqlTokens: List<SqlWordToken>,
        explainToken: SqlWordToken,
        engine: DatasourceEngine?,
    ): Boolean {
        val remaining = sqlTokens.dropWhile { token -> token.start <= explainToken.start }
        if (remaining.any { token -> token.value == "analyze" }) {
            return false
        }

        val explained =
            remaining.firstOrNull { token ->
                token.depth == explainToken.depth &&
                    token.value !in explainModifiers
            } ?: return false
        if (explained.value in blockedWords) {
            return false
        }
        return classifyMaskedSql(maskedSql.substring(explained.start), engine)
    }

    private fun isSafeStatementForValidation(
        sql: String,
        engine: DatasourceEngine?,
    ): Boolean {
        val analysis = SqlLexicalScanner.analyze(sql, engine)
        if (!analysis.valid) {
            return false
        }
        val sqlTokens = tokens(analysis.maskedSql)
        val first = firstMeaningfulToken(analysis.maskedSql, sqlTokens) ?: return true
        return first.value != "explain" || isReadOnlyExplain(analysis.maskedSql, sqlTokens, first, engine)
    }

    private fun firstMeaningfulToken(
        maskedSql: String,
        sqlTokens: List<SqlWordToken>,
    ): SqlWordToken? {
        val first = sqlTokens.firstOrNull() ?: return null
        val prefix = maskedSql.substring(0, first.start)
        return first.takeIf { prefix.all { char -> char.isWhitespace() || char == '(' || char == ';' } }
    }

    private fun tokens(maskedSql: String): List<SqlWordToken> {
        val result = mutableListOf<SqlWordToken>()
        var depth = 0
        var index = 0
        while (index < maskedSql.length) {
            when (maskedSql[index]) {
                '(' -> {
                    depth += 1
                    index += 1
                }

                ')' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    index += 1
                }

                else -> {
                    val match = wordRegex.find(maskedSql, index)
                    if (match == null) {
                        break
                    }
                    for (cursor in index until match.range.first) {
                        when (maskedSql[cursor]) {
                            '(' -> depth += 1
                            ')' -> depth = (depth - 1).coerceAtLeast(0)
                        }
                    }
                    result +=
                        SqlWordToken(
                            value = match.value.lowercase(Locale.ROOT),
                            start = match.range.first,
                            depth = depth,
                        )
                    index = match.range.last + 1
                }
            }
        }
        return result
    }

    private data class SqlWordToken(
        val value: String,
        val start: Int,
        val depth: Int,
    ) {
        val end: Int = start + value.length
    }

    private data class CteBody(
        val sql: String,
        val endExclusive: Int,
    )
}
