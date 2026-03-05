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
class VerticaSystemHealthProvider : SystemHealthProvider {
    override val engines: Set<DatasourceEngine> = setOf(DatasourceEngine.VERTICA)

    override fun check(
        spec: ConnectionSpec,
        connection: Connection,
    ): SystemHealthCheckResult {
        val details = mutableMapOf<String, Any?>()

        runCatching { queryString(connection, "SELECT version()", fallback = null) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { version ->
                details["serverVersion"] = version
            }

        runCatching {
            queryRow(
                connection,
                """
                SELECT database_name,
                       start_time,
                       load_balance_policy
                FROM v_catalog.databases
                LIMIT 1
                """.trimIndent(),
            )
        }.getOrNull()?.let { databaseRow ->
            databaseRow["database_name"]?.let { details["databaseName"] = it }
            databaseRow["start_time"]?.let { details["databaseStartTime"] = it }
            databaseRow["load_balance_policy"]?.let { details["loadBalancePolicy"] = it }
        }

        val nodeRows =
            try {
                queryRows(
                    connection,
                    """
                    SELECT node_name,
                           node_state,
                           node_type,
                           node_address,
                           last_msg_from_node_at,
                           node_down_since
                    FROM v_catalog.nodes
                    ORDER BY node_name
                    """.trimIndent(),
                )
            } catch (exception: SQLException) {
                return handleQueryFailure(exception, spec, "v_catalog.nodes")
            }

        val nodes =
            nodeRows.mapIndexed { index, row ->
                val name = row["node_name"]?.takeIf { it.isNotBlank() } ?: "node-${index + 1}"
                val status = row["node_state"]?.takeIf { it.isNotBlank() } ?: "UNKNOWN"
                val role = row["node_type"]?.takeIf { it.isNotBlank() }
                SystemHealthNode(
                    name = name,
                    role = role,
                    status = status,
                    details =
                        buildMap {
                            row["node_address"]?.takeIf { it.isNotBlank() }?.let { put("address", it) }
                            row["last_msg_from_node_at"]?.takeIf { it.isNotBlank() }?.let {
                                put(
                                    "lastMsgFromNodeAt",
                                    it,
                                )
                            }
                            row["node_down_since"]?.takeIf { it.isNotBlank() }?.let { put("downSince", it) }
                        },
                )
            }

        val notUp =
            nodes.filterNot { node ->
                node.status.equals("UP", ignoreCase = true)
            }

        details["nodes"] = nodes.size
        details["nodesUp"] = nodes.size - notUp.size

        return SystemHealthCheckResult(
            status = if (notUp.isEmpty()) SystemHealthStatus.OK else SystemHealthStatus.ERROR,
            message =
                if (notUp.isEmpty()) {
                    null
                } else {
                    "Vertica cluster has ${notUp.size} node(s) not UP."
                },
            nodes = nodes,
            details = details,
        )
    }

    private fun handleQueryFailure(
        exception: SQLException,
        spec: ConnectionSpec,
        resource: String,
    ): SystemHealthCheckResult {
        if (isInsufficientPrivilege(exception)) {
            return SystemHealthCheckResult(
                status = SystemHealthStatus.INSUFFICIENT_PRIVILEGES,
                message =
                    "Connection does not have privileges to query '$resource' on ${spec.datasourceName}. Use a sysadmin credential profile to enable Vertica cluster checks.",
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
            message = exception.message ?: "Vertica health query failed.",
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

    private fun queryString(
        connection: Connection,
        sql: String,
        fallback: String?,
    ): String? =
        connection.createStatement().use { statement ->
            statement.queryTimeout = 10
            statement.executeQuery(sql).use { resultSet ->
                if (!resultSet.next()) {
                    return fallback
                }
                resultSet.getString(1) ?: fallback
            }
        }

    private fun queryRow(
        connection: Connection,
        sql: String,
    ): Map<String, String?> =
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
                if (!resultSet.next()) {
                    return emptyMap()
                }
                toRow(resultSet, columns)
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
}
