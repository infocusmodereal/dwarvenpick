package com.dwarvenpick.app.systemhealth.providers

import com.dwarvenpick.app.datasource.ConnectionSpec
import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.systemhealth.SystemHealthCheckResult
import com.dwarvenpick.app.systemhealth.SystemHealthNode
import com.dwarvenpick.app.systemhealth.SystemHealthProvider
import com.dwarvenpick.app.systemhealth.SystemHealthStatus
import com.dwarvenpick.app.systemhealth.isInsufficientPrivilege
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

@Component
class StarRocksSystemHealthProvider : SystemHealthProvider {
    override val engines: Set<DatasourceEngine> = setOf(DatasourceEngine.STARROCKS)

    override fun check(
        spec: ConnectionSpec,
        connection: Connection,
    ): SystemHealthCheckResult {
        val details = mutableMapOf<String, Any?>()

        val feRows =
            try {
                queryRows(connection, "SHOW FRONTENDS")
            } catch (exception: SQLException) {
                return handleQueryFailure(exception, spec, "SHOW FRONTENDS")
            }
        val beRows =
            try {
                queryRows(connection, "SHOW BACKENDS")
            } catch (exception: SQLException) {
                return handleQueryFailure(exception, spec, "SHOW BACKENDS")
            }

        val nodes = mutableListOf<SystemHealthNode>()

        feRows.forEachIndexed { index, row ->
            val host = row["host"]?.takeIf { it.isNotBlank() } ?: row["name"] ?: "frontend-${index + 1}"
            val alive = parseTruth(row["alive"])
            val status = if (alive == false) "DOWN" else "UP"
            nodes.add(
                SystemHealthNode(
                    name = host,
                    role = "frontend",
                    status = status,
                    details =
                        mapOf(
                            "role" to row["role"],
                            "isMaster" to row["ismaster"],
                            "alive" to row["alive"],
                        ),
                ),
            )
        }

        beRows.forEachIndexed { index, row ->
            val host = row["host"]?.takeIf { it.isNotBlank() } ?: row["backendid"] ?: "backend-${index + 1}"
            val alive = parseTruth(row["alive"])
            val status = if (alive == false) "DOWN" else "UP"
            nodes.add(
                SystemHealthNode(
                    name = host,
                    role = "backend",
                    status = status,
                    details =
                        mapOf(
                            "backendId" to row["backendid"],
                            "alive" to row["alive"],
                            "errMsg" to row["errmsg"],
                        ),
                ),
            )
        }

        details["frontends"] = feRows.size
        details["backends"] = beRows.size

        return SystemHealthCheckResult(
            status = SystemHealthStatus.OK,
            message = null,
            nodes = nodes,
            details = details,
        )
    }

    private fun handleQueryFailure(
        exception: SQLException,
        spec: ConnectionSpec,
        query: String,
    ): SystemHealthCheckResult {
        if (isInsufficientPrivilege(exception)) {
            return SystemHealthCheckResult(
                status = SystemHealthStatus.INSUFFICIENT_PRIVILEGES,
                message =
                    "Connection does not have privileges to run '$query' on ${spec.datasourceName}. Use an admin credential profile to enable StarRocks cluster checks.",
                nodes =
                    listOf(
                        SystemHealthNode(
                            name = spec.datasourceName,
                            role = "cluster",
                            status = "UNKNOWN",
                        ),
                    ),
            )
        }

        return SystemHealthCheckResult(
            status = SystemHealthStatus.ERROR,
            message = exception.message ?: "StarRocks health query failed.",
            nodes =
                listOf(
                    SystemHealthNode(
                        name = spec.datasourceName,
                        role = "cluster",
                        status = "UNKNOWN",
                    ),
                ),
        )
    }

    private fun queryRows(
        connection: Connection,
        sql: String,
    ): List<Map<String, String?>> =
        connection.createStatement().use { statement ->
            statement.queryTimeout = 10
            statement.executeQuery(sql).use { resultSet ->
                val meta = resultSet.metaData
                val columns =
                    (1..meta.columnCount)
                        .map { index ->
                            meta.getColumnLabel(index)?.ifBlank { meta.getColumnName(index) }
                                ?: "col$index"
                        }

                val rows = mutableListOf<Map<String, String?>>()
                while (resultSet.next()) {
                    rows.add(toRow(resultSet, columns))
                }
                rows
            }
        }

    private fun toRow(
        resultSet: ResultSet,
        columns: List<String>,
    ): Map<String, String?> =
        buildMap {
            columns.forEachIndexed { index, column ->
                put(column.lowercase(), resultSet.getString(index + 1))
            }
        }

    private fun parseTruth(value: String?): Boolean? {
        val normalized = value?.trim()?.lowercase() ?: return null
        if (normalized.isBlank()) {
            return null
        }
        return normalized == "true" || normalized == "yes" || normalized == "y" || normalized == "1"
    }
}
