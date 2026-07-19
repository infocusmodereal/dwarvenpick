package com.dwarvenpick.app.query

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
class QueryResultStorageMetrics(
    private val queryResultPersistenceRepository: QueryResultPersistenceRepository,
    private val queryExecutionProperties: QueryExecutionProperties,
    private val meterRegistry: MeterRegistry,
) {
    private val usedBytes = AtomicLong(0)
    private val tableBytes = AtomicLong(0)
    private val logger = LoggerFactory.getLogger(QueryResultStorageMetrics::class.java)

    @PostConstruct
    fun registerMetrics() {
        meterRegistry.gauge("dwarvenpick.query.result_storage.used.bytes", usedBytes) { value -> value.get().toDouble() }
        meterRegistry.gauge("dwarvenpick.query.result_storage.table.bytes", tableBytes) { value -> value.get().toDouble() }
        meterRegistry.gauge("dwarvenpick.query.result_storage.budget.bytes", queryExecutionProperties) { properties ->
            properties.maxPersistedResultBytes.toDouble()
        }
        meterRegistry.gauge("dwarvenpick.query.result_storage.headroom.bytes", this) { metrics ->
            (metrics.queryExecutionProperties.maxPersistedResultBytes - metrics.usedBytes.get())
                .coerceAtLeast(0)
                .toDouble()
        }
    }

    @Scheduled(
        fixedDelayString = "\${dwarvenpick.query.result-storage-metrics-interval-ms:30000}",
        initialDelayString = "\${dwarvenpick.query.result-storage-metrics-interval-ms:30000}",
    )
    fun refresh() {
        runCatching { queryResultPersistenceRepository.storageSnapshot() }
            .onSuccess { snapshot ->
                usedBytes.set(snapshot.usedBytes)
                tableBytes.set(snapshot.tableBytes)
            }.onFailure { exception ->
                logger.warn("Unable to refresh query result storage metrics.", exception)
            }
    }
}
