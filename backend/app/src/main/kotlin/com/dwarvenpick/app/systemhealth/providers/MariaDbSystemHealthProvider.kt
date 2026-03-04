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
import java.sql.SQLException

@Component
class MariaDbSystemHealthProvider : SystemHealthProvider {
    override val engines: Set<DatasourceEngine> = setOf(DatasourceEngine.MARIADB, DatasourceEngine.MYSQL)

    override fun check(
        spec: ConnectionSpec,
        connection: Connection,
    ): SystemHealthCheckResult {
        val details = mutableMapOf<String, Any?>()

        val host =
            runCatching { queryString(connection, "SELECT @@hostname", fallback = null) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: spec.datasourceName
        val version =
            runCatching {
                queryString(connection, "SELECT @@version", fallback = null)
            }.getOrNull()
        if (version != null) {
            details["serverVersion"] = version
        }

        val uptimeSeconds =
            runCatching {
                queryStatusValue(connection, "Uptime")?.toLongOrNull()
            }.getOrNull()
        if (uptimeSeconds != null) {
            details["uptimeSeconds"] = uptimeSeconds
        }

        val readOnly =
            runCatching {
                queryString(connection, "SELECT @@read_only", fallback = null)
            }.getOrNull()
        if (readOnly != null) {
            details["readOnly"] = parseTruth(readOnly)
        }

        val wsrepOn =
            runCatching {
                queryVariableValue(connection, "wsrep_on")
            }.getOrNull()
        val galeraDetected = wsrepOn?.equals("ON", ignoreCase = true) == true

        val clusterSize =
            if (galeraDetected) {
                runCatching {
                    queryStatusValue(connection, "wsrep_cluster_size")?.toIntOrNull()
                }.getOrNull()
            } else {
                null
            }
        val incoming =
            if (galeraDetected) {
                runCatching {
                    queryStatusValue(connection, "wsrep_incoming_addresses")
                }.getOrNull()
            } else {
                null
            }
        val clusterStatus =
            if (galeraDetected) {
                runCatching {
                    queryStatusValue(connection, "wsrep_cluster_status")
                }.getOrNull()
            } else {
                null
            }
        val ready =
            if (galeraDetected) {
                runCatching {
                    queryStatusValue(connection, "wsrep_ready")
                }.getOrNull()
            } else {
                null
            }
        val connected =
            if (galeraDetected) {
                runCatching {
                    queryStatusValue(connection, "wsrep_connected")
                }.getOrNull()
            } else {
                null
            }
        val localState =
            if (galeraDetected) {
                runCatching {
                    queryStatusValue(connection, "wsrep_local_state")
                }.getOrNull()
            } else {
                null
            }
        val localStateComment =
            if (galeraDetected) {
                runCatching {
                    queryStatusValue(connection, "wsrep_local_state_comment")
                }.getOrNull()
            } else {
                null
            }
        val evsState =
            if (galeraDetected) {
                runCatching {
                    queryStatusValue(connection, "wsrep_evs_state")
                }.getOrNull()
            } else {
                null
            }

        if (galeraDetected) {
            if (clusterSize != null) {
                details["wsrepClusterSize"] = clusterSize
            }
            if (!incoming.isNullOrBlank()) {
                details["wsrepIncomingAddresses"] = incoming
            }
            if (!clusterStatus.isNullOrBlank()) {
                details["wsrepClusterStatus"] = clusterStatus
            }
            if (!ready.isNullOrBlank()) {
                details["wsrepReady"] = ready
            }
            if (!connected.isNullOrBlank()) {
                details["wsrepConnected"] = connected
            }
            if (!localState.isNullOrBlank()) {
                details["wsrepLocalState"] = localState
            }
            if (!localStateComment.isNullOrBlank()) {
                details["wsrepLocalStateComment"] = localStateComment
            }
            if (!evsState.isNullOrBlank()) {
                details["wsrepEvsState"] = evsState
            }
        }

        val galeraIssues = mutableListOf<String>()
        if (!clusterStatus.isNullOrBlank() && !clusterStatus.equals("Primary", ignoreCase = true)) {
            galeraIssues.add("cluster status=$clusterStatus")
        }
        if (!ready.isNullOrBlank() && !ready.equals("ON", ignoreCase = true)) {
            galeraIssues.add("ready=$ready")
        }
        if (!connected.isNullOrBlank() && !connected.equals("ON", ignoreCase = true)) {
            galeraIssues.add("connected=$connected")
        }
        if (!localStateComment.isNullOrBlank() && !localStateComment.equals("Synced", ignoreCase = true)) {
            galeraIssues.add("state=$localStateComment")
        }

        val nodes =
            if (galeraDetected) {
                val nodeStatus =
                    if (galeraIssues.isEmpty()) {
                        "UP"
                    } else {
                        "DEGRADED"
                    }

                incoming
                    ?.takeIf { it.isNotBlank() }
                    ?.split(',')
                    ?.mapNotNull { address ->
                        val trimmed = address.trim()
                        trimmed.takeIf { it.isNotBlank() }
                    }?.ifEmpty { listOf(host) }
                    ?.distinct()
                    ?.map { node ->
                        SystemHealthNode(
                            name = node,
                            role = "member",
                            status = nodeStatus,
                        )
                    }
                    ?: listOf(
                        SystemHealthNode(
                            name = host,
                            role = "member",
                            status = nodeStatus,
                        ),
                    )
            } else {
                listOf(
                    SystemHealthNode(
                        name = host,
                        role = "primary",
                        status = "UP",
                    ),
                )
            }

        return SystemHealthCheckResult(
            status =
                if (galeraDetected && galeraIssues.isNotEmpty()) {
                    SystemHealthStatus.ERROR
                } else {
                    SystemHealthStatus.OK
                },
            message =
                if (galeraDetected && galeraIssues.isNotEmpty()) {
                    "Galera cluster is not healthy (${galeraIssues.joinToString(", ")})."
                } else {
                    null
                },
            nodes = nodes,
            details = details,
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

    private fun queryStatusValue(
        connection: Connection,
        variableName: String,
    ): String? =
        try {
            val escaped = variableName.replace("'", "''")
            connection.createStatement().use { statement ->
                statement.queryTimeout = 10
                statement.executeQuery("SHOW STATUS LIKE '$escaped'").use { resultSet ->
                    if (!resultSet.next()) {
                        return null
                    }
                    resultSet.getString(2)
                }
            }
        } catch (exception: SQLException) {
            if (isInsufficientPrivilege(exception)) {
                null
            } else {
                throw exception
            }
        }

    private fun queryVariableValue(
        connection: Connection,
        variableName: String,
    ): String? =
        try {
            val escaped = variableName.replace("'", "''")
            connection.createStatement().use { statement ->
                statement.queryTimeout = 10
                statement.executeQuery("SHOW VARIABLES LIKE '$escaped'").use { resultSet ->
                    if (!resultSet.next()) {
                        return null
                    }
                    resultSet.getString(2)
                }
            }
        } catch (exception: SQLException) {
            if (isInsufficientPrivilege(exception)) {
                null
            } else {
                throw exception
            }
        }

    private fun parseTruth(value: String?): Boolean? {
        val normalized = value?.trim()?.lowercase() ?: return null
        if (normalized.isBlank()) {
            return null
        }
        return normalized == "true" || normalized == "yes" || normalized == "y" || normalized == "1" || normalized == "on"
    }
}
