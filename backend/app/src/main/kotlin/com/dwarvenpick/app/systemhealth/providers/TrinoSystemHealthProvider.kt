package com.dwarvenpick.app.systemhealth.providers

import com.dwarvenpick.app.datasource.ConnectionSpec
import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.systemhealth.SystemHealthCheckResult
import com.dwarvenpick.app.systemhealth.SystemHealthNode
import com.dwarvenpick.app.systemhealth.SystemHealthProvider
import com.dwarvenpick.app.systemhealth.SystemHealthStatus
import com.dwarvenpick.app.systemhealth.isInsufficientPrivilege
import org.springframework.stereotype.Component
import java.net.URI
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

@Component
class TrinoSystemHealthProvider : SystemHealthProvider {
    override val engines: Set<DatasourceEngine> = setOf(DatasourceEngine.TRINO)

    override fun check(
        spec: ConnectionSpec,
        connection: Connection,
    ): SystemHealthCheckResult {
        val details = mutableMapOf<String, Any?>()

        val trinoVersion =
            runCatching {
                queryString(connection, "SELECT version()")
            }.getOrNull()
        if (!trinoVersion.isNullOrBlank()) {
            details["serverVersion"] = trinoVersion
        }

        val nodeRows =
            try {
                queryRows(connection, "SELECT * FROM system.runtime.nodes")
            } catch (exception: SQLException) {
                if (isInsufficientPrivilege(exception)) {
                    return SystemHealthCheckResult(
                        status = SystemHealthStatus.INSUFFICIENT_PRIVILEGES,
                        message =
                            "Connection does not have privileges to read system.runtime.nodes on ${spec.datasourceName}. " +
                                "Grant access to the system.runtime schema (or use an admin credential profile) to enable Trino cluster checks.",
                        nodes =
                            listOf(
                                SystemHealthNode(
                                    name = spec.datasourceName,
                                    role = "cluster",
                                    status = "UNKNOWN",
                                ),
                            ),
                        details = details,
                    )
                }

                return SystemHealthCheckResult(
                    status = SystemHealthStatus.ERROR,
                    message = exception.message ?: "Trino health query failed.",
                    nodes =
                        listOf(
                            SystemHealthNode(
                                name = spec.datasourceName,
                                role = "cluster",
                                status = "UNKNOWN",
                            ),
                        ),
                    details = details,
                )
            }

        val nodes = nodeRows.mapIndexed { index, row -> toNode(row, index) }
        details["nodes"] = nodes.size
        details["coordinators"] = nodes.count { node -> node.role == "coordinator" }

        return SystemHealthCheckResult(
            status = SystemHealthStatus.OK,
            message = null,
            nodes = nodes,
            details = details,
        )
    }

    private fun toNode(
        row: Map<String, String?>,
        index: Int,
    ): SystemHealthNode {
        val coordinator = parseTruth(row["coordinator"])
        val role = if (coordinator == true) "coordinator" else "worker"

        val state = row["state"]?.trim()?.ifBlank { null } ?: "unknown"
        val status =
            when (state.lowercase()) {
                "active" -> "UP"
                "inactive" -> "DOWN"
                "shutting_down" -> "DEGRADED"
                else -> "UNKNOWN"
            }

        val httpUri = row["http_uri"] ?: row["httpuri"]
        val name =
            httpUri
                ?.takeIf { it.isNotBlank() }
                ?.let { uri ->
                    runCatching {
                        URI(uri).host?.takeIf { it.isNotBlank() } ?: uri
                    }.getOrDefault(uri)
                }
                ?: row["node_id"]
                ?: row["nodeid"]
                ?: "node-${index + 1}"

        val details =
            buildMap<String, Any?> {
                put("state", state)
                row["node_version"]?.takeIf { it.isNotBlank() }?.let { put("nodeVersion", it) }
                row["version"]?.takeIf { it.isNotBlank() }?.let { put("nodeVersion", it) }
                row["http_uri"]?.takeIf { it.isNotBlank() }?.let { put("httpUri", it) }
            }

        return SystemHealthNode(
            name = name,
            role = role,
            status = status,
            details = details,
        )
    }

    private fun queryString(
        connection: Connection,
        sql: String,
    ): String? =
        connection.createStatement().use { statement ->
            statement.queryTimeout = 10
            statement.executeQuery(sql).use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }
                resultSet.getString(1)
            }
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
