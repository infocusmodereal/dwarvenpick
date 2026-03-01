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

        val clusterSize =
            runCatching {
                queryStatusValue(connection, "wsrep_cluster_size")?.toIntOrNull()
            }.getOrNull()
        val incoming =
            runCatching {
                queryStatusValue(connection, "wsrep_incoming_addresses")
            }.getOrNull()
        val clusterStatus =
            runCatching {
                queryStatusValue(connection, "wsrep_cluster_status")
            }.getOrNull()
        val ready =
            runCatching {
                queryStatusValue(connection, "wsrep_ready")
            }.getOrNull()

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

        val nodes =
            if (!incoming.isNullOrBlank()) {
                incoming
                    .split(',')
                    .mapNotNull { address ->
                        val trimmed = address.trim()
                        trimmed.takeIf { it.isNotBlank() }
                    }.ifEmpty { listOf(host) }
                    .distinct()
                    .map { node ->
                        SystemHealthNode(
                            name = node,
                            role = "member",
                            status =
                                if (ready?.equals("ON", ignoreCase = true) == true) {
                                    "UP"
                                } else {
                                    "DEGRADED"
                                },
                        )
                    }
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
            status = SystemHealthStatus.OK,
            message = null,
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

    private fun parseTruth(value: String?): Boolean? {
        val normalized = value?.trim()?.lowercase() ?: return null
        if (normalized.isBlank()) {
            return null
        }
        return normalized == "true" || normalized == "yes" || normalized == "y" || normalized == "1" || normalized == "on"
    }
}
