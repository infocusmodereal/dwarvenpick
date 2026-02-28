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
class PostgresSystemHealthProvider : SystemHealthProvider {
    override val engines: Set<DatasourceEngine> = setOf(DatasourceEngine.POSTGRESQL)

    override fun check(
        spec: ConnectionSpec,
        connection: Connection,
    ): SystemHealthCheckResult {
        val nodes = mutableListOf<SystemHealthNode>()
        val details = mutableMapOf<String, Any?>()

        val inRecovery =
            runCatching {
                queryBoolean(connection, "SELECT pg_is_in_recovery()")
            }.getOrDefault(false)
        val role = if (inRecovery) "standby" else "primary"

        val version =
            runCatching {
                queryString(connection, "SELECT current_setting('server_version')", fallback = null)
            }.getOrNull()
        if (version != null) {
            details["serverVersion"] = version
        }

        nodes.add(
            SystemHealthNode(
                name = spec.datasourceName,
                role = role,
                status = "UP",
                details =
                    mapOf(
                        "datasourceId" to spec.datasourceId,
                        "credentialProfile" to spec.credentialProfile,
                    ),
            ),
        )

        if (!inRecovery) {
            try {
                val replication =
                    queryRows(
                        connection,
                        """
                        SELECT client_addr::text AS client_addr,
                               state AS state,
                               sync_state AS sync_state
                        FROM pg_stat_replication
                        """.trimIndent(),
                    )
                replication.forEachIndexed { index, row ->
                    val addr = row["client_addr"]?.takeIf { it.isNotBlank() } ?: "standby-${index + 1}"
                    val state = row["state"] ?: "unknown"
                    val syncState = row["sync_state"] ?: "unknown"
                    val status = if (state.equals("streaming", ignoreCase = true)) "UP" else "DEGRADED"
                    nodes.add(
                        SystemHealthNode(
                            name = addr,
                            role = "standby",
                            status = status,
                            details =
                                mapOf(
                                    "state" to state,
                                    "syncState" to syncState,
                                ),
                        ),
                    )
                }
            } catch (exception: SQLException) {
                if (isInsufficientPrivilege(exception)) {
                    return SystemHealthCheckResult(
                        status = SystemHealthStatus.INSUFFICIENT_PRIVILEGES,
                        message =
                            "Connection does not have privileges to read pg_stat_replication. Grant pg_monitor (or equivalent) to enable replication health checks.",
                        nodes = nodes,
                        details = details,
                    )
                }
                return SystemHealthCheckResult(
                    status = SystemHealthStatus.ERROR,
                    message = exception.message ?: "PostgreSQL health query failed.",
                    nodes = nodes,
                    details = details,
                )
            }
        } else {
            try {
                val receiver =
                    queryRows(
                        connection,
                        """
                        SELECT status AS status,
                               conninfo AS conninfo
                        FROM pg_stat_wal_receiver
                        """.trimIndent(),
                    ).firstOrNull()

                if (receiver != null) {
                    details["walReceiverStatus"] = receiver["status"]
                }
            } catch (exception: SQLException) {
                if (isInsufficientPrivilege(exception)) {
                    return SystemHealthCheckResult(
                        status = SystemHealthStatus.INSUFFICIENT_PRIVILEGES,
                        message =
                            "Connection does not have privileges to read pg_stat_wal_receiver. Grant pg_monitor (or equivalent) to enable standby health checks.",
                        nodes = nodes,
                        details = details,
                    )
                }
                return SystemHealthCheckResult(
                    status = SystemHealthStatus.ERROR,
                    message = exception.message ?: "PostgreSQL health query failed.",
                    nodes = nodes,
                    details = details,
                )
            }
        }

        return SystemHealthCheckResult(
            status = SystemHealthStatus.OK,
            message = null,
            nodes = nodes,
            details = details,
        )
    }

    private fun queryBoolean(
        connection: Connection,
        sql: String,
    ): Boolean =
        connection.createStatement().use { statement ->
            statement.queryTimeout = 10
            statement.executeQuery(sql).use { resultSet ->
                if (!resultSet.next()) {
                    return false
                }

                resultSet.getBoolean(1)
            }
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
