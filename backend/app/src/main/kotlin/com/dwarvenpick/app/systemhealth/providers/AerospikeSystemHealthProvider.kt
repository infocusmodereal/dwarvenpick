package com.dwarvenpick.app.systemhealth.providers

import com.aerospike.client.AerospikeClient
import com.aerospike.client.Info
import com.aerospike.client.policy.ClientPolicy
import com.dwarvenpick.app.datasource.ConnectionSpec
import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.systemhealth.SystemHealthCheckResult
import com.dwarvenpick.app.systemhealth.SystemHealthNode
import com.dwarvenpick.app.systemhealth.SystemHealthProvider
import com.dwarvenpick.app.systemhealth.SystemHealthStatus
import org.springframework.stereotype.Component
import java.sql.Connection

@Component
class AerospikeSystemHealthProvider : SystemHealthProvider {
    override val engines: Set<DatasourceEngine> = setOf(DatasourceEngine.AEROSPIKE)

    override fun check(
        spec: ConnectionSpec,
        connection: Connection,
    ): SystemHealthCheckResult {
        val nodes = mutableListOf<SystemHealthNode>()
        val details = mutableMapOf<String, Any?>()

        runCatching {
            val metadata = connection.metaData
            details["jdbcDriverVersion"] = metadata.driverVersion
            details["serverVersion"] = metadata.databaseProductVersion
            details["productName"] = metadata.databaseProductName
        }

        val target = parseJdbcTarget(spec.jdbcUrl)
        details["host"] = target.host
        details["port"] = target.port
        target.namespace?.let { namespace -> details["namespace"] = namespace }

        val policy =
            ClientPolicy().apply {
                if (spec.username.isNotBlank()) {
                    user = spec.username
                }
                if (spec.password.isNotBlank()) {
                    password = spec.password
                }
                loginTimeout = 3_000
                timeout = 1_000
            }

        AerospikeClient(policy, target.host, target.port).use { client ->
            val clusterName = requestInfoSafely(client, "cluster-name")
            if (!clusterName.isNullOrBlank()) {
                details["clusterName"] = clusterName
            }

            client.nodes.forEach { node ->
                val build = runCatching { Info.request(node, "build") }.getOrNull()
                val statistics = runCatching { Info.request(node, "statistics") }.getOrNull()
                val uptime = statistics?.let { info -> parseInfoValue(info, "uptime") }
                val rack = statistics?.let { info -> parseInfoValue(info, "rack-id") }

                nodes.add(
                    SystemHealthNode(
                        name = node.name,
                        role = "node",
                        status = "UP",
                        details =
                            buildMap {
                                put("build", build)
                                put("uptime", uptime)
                                put("rack", rack)
                            },
                    ),
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

    private data class JdbcTarget(
        val host: String,
        val port: Int,
        val namespace: String?,
    )

    private fun parseJdbcTarget(jdbcUrl: String): JdbcTarget {
        val pattern = Regex("^jdbc:aerospike:([^:/?#]+)(?::(\\d+))?(?:/([^?]+))?.*", RegexOption.IGNORE_CASE)
        val match =
            pattern.find(jdbcUrl.trim())
                ?: throw IllegalArgumentException("Unsupported Aerospike JDBC URL.")

        val host = match.groupValues[1]
        val port =
            match.groupValues
                .getOrNull(2)
                ?.takeIf { value -> value.isNotBlank() }
                ?.toInt() ?: 3000
        val namespace = match.groupValues.getOrNull(3)?.takeIf { value -> value.isNotBlank() }
        return JdbcTarget(
            host = host,
            port = port,
            namespace = namespace,
        )
    }

    private fun requestInfoSafely(
        client: AerospikeClient,
        command: String,
    ): String? =
        runCatching {
            client.nodes.firstOrNull()?.let { node ->
                Info.request(node, command)
            }
        }.getOrNull()

    private fun parseInfoValue(
        info: String,
        key: String,
    ): String? {
        val prefix = "$key="
        return info
            .split(';')
            .firstOrNull { segment -> segment.startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.takeIf { value -> value.isNotBlank() }
    }
}
