package com.dwarvenpick.app.query

import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.datasource.DatasourcePoolManager
import com.dwarvenpick.app.rbac.QueryAccessPolicy
import org.springframework.stereotype.Service
import java.sql.SQLException

@Service
class QueryValidationService(
    private val datasourcePoolManager: DatasourcePoolManager,
) {
    fun validate(
        request: QueryValidationRequest,
        policy: QueryAccessPolicy,
    ): QueryValidationResponse {
        val sql = request.sql.trim()
        if (sql.isBlank()) {
            return QueryValidationResponse(valid = false, message = "SQL is empty.")
        }

        if (policy.readOnly && !SqlSafety.isReadOnlySql(sql)) {
            return QueryValidationResponse(
                valid = false,
                message =
                    "Read-only mode is enabled for this connection access mapping. Only SELECT-like statements are allowed.",
            )
        }

        val handle =
            datasourcePoolManager.openConnection(
                datasourceId = request.datasourceId.trim(),
                credentialProfile = policy.credentialProfile,
            )

        return handle.connection.use { connection ->
            // Prefer EXPLAIN-based validation for SELECT-like statements so we avoid executing work.
            val explainSql = buildExplainSql(engine = handle.spec.engine, sql = sql)
            runCatching {
                connection.createStatement().use { statement ->
                    statement.queryTimeout = 10
                    statement.execute(explainSql)
                }
                QueryValidationResponse(valid = true, message = "Validation succeeded.")
            }.getOrElse { exception ->
                val message = sanitizeErrorMessage(exception.message ?: "Validation failed.")
                val position = extractErrorPosition(sql, exception)
                QueryValidationResponse(
                    valid = false,
                    message = message,
                    line = position?.line,
                    column = position?.column,
                    position = position?.position,
                )
            }
        }
    }

    private fun buildExplainSql(
        engine: DatasourceEngine,
        sql: String,
    ): String {
        val trimmed = sql.trim().trimEnd(';').trim()
        if (trimmed.isBlank()) {
            return "SELECT 1"
        }

        if (trimmed.startsWith("explain", ignoreCase = true)) {
            return trimmed
        }

        return when (engine) {
            DatasourceEngine.POSTGRESQL -> "EXPLAIN (FORMAT JSON) $trimmed"
            DatasourceEngine.MYSQL, DatasourceEngine.MARIADB -> "EXPLAIN FORMAT=JSON $trimmed"
            DatasourceEngine.TRINO -> "EXPLAIN $trimmed"
            DatasourceEngine.STARROCKS, DatasourceEngine.VERTICA -> "EXPLAIN $trimmed"
        }
    }

    private fun sanitizeErrorMessage(message: String): String =
        message
            .replace(Regex("password\\s*=\\s*[^;\\s]+", RegexOption.IGNORE_CASE), "password=***")
            .replace(Regex("passwd\\s*=\\s*[^;\\s]+", RegexOption.IGNORE_CASE), "passwd=***")
            .replace(Regex("jdbc:[^\\s]+", RegexOption.IGNORE_CASE), "jdbc:***")
            .ifBlank { "Validation failed." }

    private data class ErrorPosition(
        val line: Int,
        val column: Int,
        val position: Int,
    )

    private fun extractErrorPosition(
        sql: String,
        exception: Throwable,
    ): ErrorPosition? {
        val message = exception.message ?: return null

        // Trino often returns: line 1:8: ... or line 1:8: ...
        Regex("\\bline\\s+(\\d+):(\\d+)\\b", RegexOption.IGNORE_CASE)
            .find(message)
            ?.let { match ->
                val line = match.groupValues[1].toIntOrNull() ?: return@let null
                val column = match.groupValues[2].toIntOrNull() ?: return@let null
                val position = computeAbsolutePosition(sql, line, column)
                return ErrorPosition(line, column, position)
            }

        // PostgreSQL: Position: 15
        Regex("\\bposition\\s*:\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)
            .find(message)
            ?.let { match ->
                val position = match.groupValues[1].toIntOrNull() ?: return@let null
                val (line, column) = computeLineColumn(sql, position)
                return ErrorPosition(line, column, position)
            }

        // MySQL: ... at line 1
        Regex("\\bat\\s+line\\s+(\\d+)\\b", RegexOption.IGNORE_CASE)
            .find(message)
            ?.let { match ->
                val line = match.groupValues[1].toIntOrNull() ?: return@let null
                val position = computeAbsolutePosition(sql, line, 1)
                return ErrorPosition(line, 1, position)
            }

        // Some drivers wrap position into SQLExceptions with error code/state only.
        if (exception is SQLException) {
            Regex("\\bposition\\s*\\((\\d+)\\)\\b", RegexOption.IGNORE_CASE)
                .find(message)
                ?.let { match ->
                    val position = match.groupValues[1].toIntOrNull() ?: return@let null
                    val (line, column) = computeLineColumn(sql, position)
                    return ErrorPosition(line, column, position)
                }
        }

        return null
    }

    private fun computeLineColumn(
        sql: String,
        position: Int,
    ): Pair<Int, Int> {
        val normalized = position.coerceAtLeast(1)
        val prefix = sql.take((normalized - 1).coerceAtMost(sql.length))
        val lines = prefix.split('\n')
        val lineNumber = lines.size.coerceAtLeast(1)
        val column = (lines.lastOrNull()?.length ?: 0) + 1
        return lineNumber to column
    }

    private fun computeAbsolutePosition(
        sql: String,
        line: Int,
        column: Int,
    ): Int {
        if (line <= 1) {
            return column.coerceAtLeast(1)
        }

        var remainingLines = line - 1
        var offset = 0
        while (remainingLines > 0 && offset < sql.length) {
            val newlineIndex = sql.indexOf('\n', startIndex = offset)
            if (newlineIndex < 0) {
                break
            }
            offset = newlineIndex + 1
            remainingLines -= 1
        }

        return (offset + column).coerceAtLeast(1)
    }
}
