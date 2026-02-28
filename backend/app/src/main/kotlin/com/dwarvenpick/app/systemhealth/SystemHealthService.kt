package com.dwarvenpick.app.systemhealth

import com.dwarvenpick.app.datasource.DatasourcePoolManager
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class SystemHealthService(
    private val datasourcePoolManager: DatasourcePoolManager,
    private val providers: List<SystemHealthProvider>,
) {
    fun check(
        datasourceId: String,
        credentialProfile: String,
    ): SystemHealthResponse {
        val checkedAt = Instant.now()
        val handle =
            datasourcePoolManager.openConnection(
                datasourceId = datasourceId,
                credentialProfile = credentialProfile,
            )

        val spec = handle.spec
        val provider = providers.firstOrNull { candidate -> spec.engine in candidate.engines }
        if (provider == null) {
            handle.connection.close()
            return SystemHealthResponse(
                datasourceId = spec.datasourceId,
                datasourceName = spec.datasourceName,
                engine = spec.engine,
                credentialProfile = spec.credentialProfile,
                checkedAt = checkedAt.toString(),
                status = SystemHealthStatus.UNSUPPORTED,
                message = "System health checks are not implemented yet for engine ${spec.engine}.",
                nodeCount = 0,
                healthyNodeCount = 0,
            )
        }

        val result =
            handle.connection.use { connection ->
                runCatching { provider.check(spec, connection) }
                    .getOrElse { exception ->
                        val message = exception.message?.takeIf { it.isNotBlank() } ?: "Health check failed."
                        SystemHealthCheckResult(
                            status = SystemHealthStatus.ERROR,
                            message = sanitizeExceptionMessage(message),
                        )
                    }
            }

        val nodeCount = result.nodes.size
        val healthyNodeCount =
            result.nodes.count { node ->
                node.status.equals("UP", ignoreCase = true) ||
                    node.status.equals("HEALTHY", ignoreCase = true) ||
                    node.status.equals("OK", ignoreCase = true)
            }

        return SystemHealthResponse(
            datasourceId = spec.datasourceId,
            datasourceName = spec.datasourceName,
            engine = spec.engine,
            credentialProfile = spec.credentialProfile,
            checkedAt = checkedAt.toString(),
            status = result.status,
            message = result.message,
            nodeCount = nodeCount,
            healthyNodeCount = healthyNodeCount,
            nodes = result.nodes,
            details = result.details,
        )
    }

    private fun sanitizeExceptionMessage(message: String): String =
        message
            .replace(Regex("password\\s*=\\s*[^;\\s]+", RegexOption.IGNORE_CASE), "password=***")
            .replace(Regex("passwd\\s*=\\s*[^;\\s]+", RegexOption.IGNORE_CASE), "passwd=***")
}
