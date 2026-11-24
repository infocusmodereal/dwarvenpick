package com.dwarvenpick.app.datasource

import com.dwarvenpick.app.runtime.ApplicationInstanceId
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class QueryConnectionHandle(
    val spec: ConnectionSpec,
    val connection: Connection,
) {
    internal var pool: HikariDataSource? = null

    fun close(evict: Boolean = false) {
        if (evict) {
            pool?.evictConnection(connection)
        }
        connection.close()
    }
}

@Service
class DatasourcePoolManager(
    private val datasourceRegistryService: DatasourceRegistryService,
    private val poolMetricsSnapshotRepository: PoolMetricsSnapshotRepository,
    private val applicationInstanceId: ApplicationInstanceId,
    private val meterRegistry: MeterRegistry,
    @Value("\${dwarvenpick.datasource.max-pool-connections-per-target:0}")
    private val maxPoolConnectionsPerTarget: Int = 0,
) {
    private data class ManagedPool(
        val dataSource: HikariDataSource,
        val target: Pair<String, Int>?,
    )

    private val pools = ConcurrentHashMap<String, ManagedPool>()
    private val poolCapacityLock = Any()

    fun testConnection(
        datasourceId: String,
        credentialProfile: String,
        tlsOverride: TlsSettings?,
        validationQuery: String,
    ): TestConnectionResponse {
        val spec = datasourceRegistryService.resolveConnectionSpec(datasourceId, credentialProfile, tlsOverride)
        val query = validationQuery.trim()
        val effectiveValidationQuery =
            when {
                spec.engine == DatasourceEngine.AEROSPIKE &&
                    (query.isBlank() || query.equals("SELECT 1", ignoreCase = true)) -> ""
                else -> query.ifBlank { "SELECT 1" }
            }

        if (tlsOverride == null) {
            val pool = getOrCreatePool(spec)
            return runValidation(pool, spec, effectiveValidationQuery, datasourceId, credentialProfile)
        }

        val temporaryPool = createPool(spec)
        return try {
            runValidation(temporaryPool, spec, effectiveValidationQuery, datasourceId, credentialProfile)
        } finally {
            temporaryPool.close()
        }
    }

    fun listPoolMetrics(): List<PoolMetricsResponse> =
        pools.entries
            .map { (key, managedPool) ->
                val dataSource = managedPool.dataSource
                val keyParts = key.split("::", limit = 2)
                val poolBean = dataSource.hikariPoolMXBean
                val configBean = dataSource.hikariConfigMXBean
                PoolMetricsResponse(
                    key = key,
                    datasourceId = keyParts.getOrElse(0) { "unknown" },
                    credentialProfile = keyParts.getOrElse(1) { "unknown" },
                    activeConnections = poolBean?.activeConnections ?: 0,
                    idleConnections = poolBean?.idleConnections ?: 0,
                    totalConnections = poolBean?.totalConnections ?: 0,
                    maximumPoolSize = configBean?.maximumPoolSize ?: dataSource.maximumPoolSize,
                    threadsAwaitingConnection = poolBean?.threadsAwaitingConnection ?: 0,
                )
            }.sortedBy { response -> response.key }

    @Scheduled(fixedDelayString = "\${dwarvenpick.query.pool-metrics-publish-interval-ms:15000}")
    fun publishPoolMetricsSnapshot() {
        val now = Instant.now()
        val snapshots =
            listPoolMetrics().map { metric ->
                PoolMetricsSnapshot(
                    instanceId = applicationInstanceId.value,
                    key = metric.key,
                    datasourceId = metric.datasourceId,
                    credentialProfile = metric.credentialProfile,
                    activeConnections = metric.activeConnections,
                    idleConnections = metric.idleConnections,
                    totalConnections = metric.totalConnections,
                    maximumPoolSize = metric.maximumPoolSize,
                    threadsAwaitingConnection = metric.threadsAwaitingConnection,
                    updatedAt = now,
                )
            }
        runCatching {
            poolMetricsSnapshotRepository.replaceForInstance(applicationInstanceId.value, snapshots, now)
            poolMetricsSnapshotRepository.deleteOlderThan(now.minusSeconds(120))
        }
    }

    fun evictPoolsForDatasource(datasourceId: String) {
        val keysToRemove = pools.keys.filter { key -> key.startsWith("$datasourceId::") }
        keysToRemove.forEach { key ->
            closePool(key)
        }
    }

    fun evictAllPools() {
        val keys = pools.keys.toList()
        keys.forEach { key ->
            closePool(key)
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
        ).also { handle -> handle.pool = pool }
    }

    private fun getOrCreatePool(spec: ConnectionSpec): HikariDataSource {
        val key = poolKey(spec.datasourceId, spec.credentialProfile)
        pools[key]?.let { return it.dataSource }

        if (maxPoolConnectionsPerTarget <= 0) {
            return getOrCreatePool(key, spec)
        }

        return synchronized(poolCapacityLock) {
            pools[key]?.dataSource ?: run {
                val target = requireNotNull(spec.target)
                val reservedConnections =
                    pools.values.filter { it.target == target }.sumOf { it.dataSource.maximumPoolSize.toLong() }
                check(reservedConnections + spec.pool.maximumPoolSize <= maxPoolConnectionsPerTarget) {
                    "Connection pool capacity for this database target is exhausted on this backend."
                }
                getOrCreatePool(key, spec)
            }
        }
    }

    private fun getOrCreatePool(
        key: String,
        spec: ConnectionSpec,
    ): HikariDataSource =
        pools
            .computeIfAbsent(key) {
                val pool = createPool(spec)
                registerPoolMetrics(pool, spec, key)
                ManagedPool(pool, spec.target)
            }.dataSource

    private fun closePool(key: String) {
        if (maxPoolConnectionsPerTarget > 0) {
            synchronized(poolCapacityLock) { pools.remove(key)?.dataSource?.close() }
        } else {
            pools.remove(key)?.dataSource?.close()
        }
    }

    private fun createPool(spec: ConnectionSpec): HikariDataSource {
        val config =
            HikariConfig().apply {
                jdbcUrl = spec.jdbcUrl
                if (spec.engine != DatasourceEngine.AEROSPIKE || spec.username.isNotBlank()) {
                    username = spec.username
                }
                if ((spec.engine != DatasourceEngine.TRINO && spec.engine != DatasourceEngine.AEROSPIKE) || spec.password.isNotBlank()) {
                    password = spec.password
                }
                if (spec.driverSource == "built-in") {
                    driverClassName = spec.driverClass
                }
                maximumPoolSize = spec.pool.maximumPoolSize
                minimumIdle = spec.pool.minimumIdle
                connectionTimeout = spec.pool.connectionTimeoutMs
                idleTimeout = spec.pool.idleTimeoutMs
                isAutoCommit = true
                poolName = "pool-${spec.datasourceId}-${spec.credentialProfile}"
                if (spec.engine != DatasourceEngine.AEROSPIKE) {
                    connectionTestQuery = "SELECT 1"
                }
                if (shouldDiscardUnreadMysqlStreamingResults(spec)) {
                    addDataSourceProperty("clobberStreamingResults", "true")
                }
            }

        return HikariDataSource(config)
    }

    private fun shouldDiscardUnreadMysqlStreamingResults(spec: ConnectionSpec): Boolean =
        shouldUseMysqlConnectorStreaming(spec.engine, spec.driverClass)

    private fun runValidation(
        dataSource: HikariDataSource,
        spec: ConnectionSpec,
        validationQuery: String,
        datasourceId: String,
        credentialProfile: String,
    ): TestConnectionResponse =
        runCatching {
            dataSource.connection.use { connection ->
                if (validationQuery.isNotBlank()) {
                    connection.createStatement().use { statement ->
                        statement.queryTimeout = 10
                        statement.execute(validationQuery)
                    }
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

        meterRegistry.gauge("dwarvenpick.pool.active", tags, dataSource) { source ->
            source.hikariPoolMXBean?.activeConnections?.toDouble() ?: 0.0
        }
        meterRegistry.gauge("dwarvenpick.pool.idle", tags, dataSource) { source ->
            source.hikariPoolMXBean?.idleConnections?.toDouble() ?: 0.0
        }
        meterRegistry.gauge("dwarvenpick.pool.total", tags, dataSource) { source ->
            source.hikariPoolMXBean?.totalConnections?.toDouble() ?: 0.0
        }
    }

    private fun sanitizeExceptionMessage(message: String): String =
        message
            .replace(Regex("password\\s*=\\s*[^;\\s]+", RegexOption.IGNORE_CASE), "password=***")
            .replace(Regex("passwd\\s*=\\s*[^;\\s]+", RegexOption.IGNORE_CASE), "passwd=***")

    private fun poolKey(
        datasourceId: String,
        credentialProfile: String,
    ): String = "$datasourceId::$credentialProfile"
}
