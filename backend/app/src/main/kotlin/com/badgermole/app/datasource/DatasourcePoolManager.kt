package com.badgermole.app.datasource

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Service

data class QueryConnectionHandle(
    val spec: ConnectionSpec,
    val connection: Connection,
)

@Service
class DatasourcePoolManager(
    private val datasourceRegistryService: DatasourceRegistryService,
    private val meterRegistry: MeterRegistry,
) {
    private val pools = ConcurrentHashMap<String, HikariDataSource>()

    fun testConnection(
        datasourceId: String,
        credentialProfile: String,
        tlsOverride: TlsSettings?,
        validationQuery: String,
    ): TestConnectionResponse {
        val spec = datasourceRegistryService.resolveConnectionSpec(datasourceId, credentialProfile, tlsOverride)
        val query = validationQuery.trim().ifBlank { "SELECT 1" }

        if (tlsOverride == null) {
            val pool = getOrCreatePool(spec)
            return runValidation(pool, spec, query, datasourceId, credentialProfile)
        }

        val temporaryPool = createPool(spec)
        return try {
            runValidation(temporaryPool, spec, query, datasourceId, credentialProfile)
        } finally {
            temporaryPool.close()
        }
    }

    fun listPoolMetrics(): List<PoolMetricsResponse> =
        pools.entries
            .map { (key, dataSource) ->
                val keyParts = key.split("::", limit = 2)
                val poolBean = dataSource.hikariPoolMXBean
                PoolMetricsResponse(
                    key = key,
                    datasourceId = keyParts.getOrElse(0) { "unknown" },
                    credentialProfile = keyParts.getOrElse(1) { "unknown" },
                    activeConnections = poolBean?.activeConnections ?: 0,
                    idleConnections = poolBean?.idleConnections ?: 0,
                    totalConnections = poolBean?.totalConnections ?: 0,
                )
            }.sortedBy { response -> response.key }

    fun evictPoolsForDatasource(datasourceId: String) {
        val keysToRemove = pools.keys.filter { key -> key.startsWith("$datasourceId::") }
        keysToRemove.forEach { key ->
            pools.remove(key)?.close()
        }
    }

    fun evictAllPools() {
        val keys = pools.keys.toList()
        keys.forEach { key ->
            pools.remove(key)?.close()
        }
    }

    fun openConnection(
        datasourceId: String,
        credentialProfile: String,
    ): QueryConnectionHandle {
        val spec = datasourceRegistryService.resolveConnectionSpec(datasourceId, credentialProfile, tlsOverride = null)
        val pool = getOrCreatePool(spec)
        return QueryConnectionHandle(
            spec = spec,
            connection = pool.connection,
        )
    }

    private fun getOrCreatePool(spec: ConnectionSpec): HikariDataSource {
        val key = poolKey(spec.datasourceId, spec.credentialProfile)
        return pools.computeIfAbsent(key) {
            val pool = createPool(spec)
            registerPoolMetrics(pool, spec, key)
            pool
        }
    }

    private fun createPool(spec: ConnectionSpec): HikariDataSource {
        val config =
            HikariConfig().apply {
                jdbcUrl = spec.jdbcUrl
                username = spec.username
                password = spec.password
                if (spec.driverSource == "built-in") {
                    driverClassName = spec.driverClass
                }
                maximumPoolSize = spec.pool.maximumPoolSize
                minimumIdle = spec.pool.minimumIdle
                connectionTimeout = spec.pool.connectionTimeoutMs
                idleTimeout = spec.pool.idleTimeoutMs
                isAutoCommit = true
                poolName = "pool-${spec.datasourceId}-${spec.credentialProfile}"
                connectionTestQuery = "SELECT 1"
            }

        return HikariDataSource(config)
    }

    private fun runValidation(
        dataSource: HikariDataSource,
        spec: ConnectionSpec,
        validationQuery: String,
        datasourceId: String,
        credentialProfile: String,
    ): TestConnectionResponse =
        runCatching {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.queryTimeout = 10
                    statement.execute(validationQuery)
                }
            }

            TestConnectionResponse(
                success = true,
                datasourceId = datasourceId,
                credentialProfile = credentialProfile,
                driverId = spec.driverId,
                driverClass = spec.driverClass,
                message = "Connection test succeeded.",
            )
        }.getOrElse { exception ->
            TestConnectionResponse(
                success = false,
                datasourceId = datasourceId,
                credentialProfile = credentialProfile,
                driverId = spec.driverId,
                driverClass = spec.driverClass,
                message = sanitizeExceptionMessage(exception.message ?: "Connection failed."),
            )
        }

    private fun registerPoolMetrics(
        dataSource: HikariDataSource,
        spec: ConnectionSpec,
        key: String,
    ) {
        val tags =
            Tags.of(
                "poolKey",
                key,
                "datasourceId",
                spec.datasourceId,
            )

        meterRegistry.gauge("badgermole.pool.active", tags, dataSource) { source ->
            source.hikariPoolMXBean?.activeConnections?.toDouble() ?: 0.0
        }
        meterRegistry.gauge("badgermole.pool.idle", tags, dataSource) { source ->
            source.hikariPoolMXBean?.idleConnections?.toDouble() ?: 0.0
        }
        meterRegistry.gauge("badgermole.pool.total", tags, dataSource) { source ->
            source.hikariPoolMXBean?.totalConnections?.toDouble() ?: 0.0
        }
    }

    private fun sanitizeExceptionMessage(message: String): String =
        message
            .replace(Regex("password\\s*=\\s*[^;\\s]+", RegexOption.IGNORE_CASE), "password=***")
            .replace(Regex("passwd\\s*=\\s*[^;\\s]+", RegexOption.IGNORE_CASE), "passwd=***")

    private fun poolKey(datasourceId: String, credentialProfile: String): String =
        "$datasourceId::$credentialProfile"
}
