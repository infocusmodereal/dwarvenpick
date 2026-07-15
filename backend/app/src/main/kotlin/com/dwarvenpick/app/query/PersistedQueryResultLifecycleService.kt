package com.dwarvenpick.app.query

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@Service
class PersistedQueryResultLifecycleService(
    private val repository: PersistedResultLifecycleRepository,
    private val properties: QueryExecutionProperties,
    private val meterRegistry: MeterRegistry,
) {
    private val persistedBytes = AtomicLong(0)
    private val persistedPages = AtomicLong(0)
    private val expiryCandidates = AtomicLong(0)
    private val expiryLagSeconds = AtomicLong(0)
    private val activeExportLeases = AtomicLong(0)
    private val logger = LoggerFactory.getLogger(PersistedQueryResultLifecycleService::class.java)

    @PostConstruct
    fun registerMetrics() {
        meterRegistry.gauge("dwarvenpick.query.persisted.result.bytes", persistedBytes)
        meterRegistry.gauge("dwarvenpick.query.persisted.result.pages", persistedPages)
        meterRegistry.gauge("dwarvenpick.query.persisted.result.expiry.candidates", expiryCandidates)
        meterRegistry.gauge("dwarvenpick.query.persisted.result.expiry.lag.seconds", expiryLagSeconds)
        meterRegistry.gauge("dwarvenpick.query.persisted.result.export.leases", activeExportLeases)
        meterRegistry.gauge(
            "dwarvenpick.query.persisted.result.expiry.deletion.enabled",
            properties,
        ) { configured ->
            if (configured.persistedResultExpiryDeleteEnabled) 1.0 else 0.0
        }
    }

    fun touchResultSession(executionId: String): Instant {
        val now = Instant.now()
        val active =
            repository.touchResultSession(
                executionId = executionId,
                ttlCutoff = now.minusSeconds(resultTtlSeconds()),
                accessedAt = now,
            )
        if (!active) {
            throw QueryResultsExpiredException("Result session expired. Re-run the query.")
        }
        return now
    }

    fun acquireExportLease(executionId: String): String {
        val now = Instant.now()
        val leaseId = UUID.randomUUID().toString()
        val acquired =
            repository.acquireExportLease(
                executionId = executionId,
                leaseId = leaseId,
                ttlCutoff = now.minusSeconds(resultTtlSeconds()),
                acquiredAt = now,
                expiresAt = now.plusSeconds(exportLeaseSeconds()),
            )
        if (!acquired) {
            throw QueryResultsExpiredException("Result session expired. Re-run the query.")
        }
        return leaseId
    }

    fun releaseExportLease(
        executionId: String,
        leaseId: String,
    ) {
        repository.releaseExportLease(executionId, leaseId)
    }

    fun protectExportRows(
        executionId: String,
        leaseId: String,
        rows: Iterable<List<String?>>,
    ): Iterable<List<String?>> =
        Iterable {
            val delegate = rows.iterator()
            var renewAfter = Instant.now().plusSeconds(exportLeaseRenewalSeconds())
            object : Iterator<List<String?>> {
                override fun hasNext(): Boolean {
                    renewIfDue()
                    return delegate.hasNext()
                }

                override fun next(): List<String?> {
                    if (!hasNext()) {
                        throw NoSuchElementException()
                    }
                    return delegate.next()
                }

                private fun renewIfDue() {
                    val now = Instant.now()
                    if (now.isBefore(renewAfter)) {
                        return
                    }
                    val renewed =
                        repository.renewExportLease(
                            executionId = executionId,
                            leaseId = leaseId,
                            accessedAt = now,
                            expiresAt = now.plusSeconds(exportLeaseSeconds()),
                        )
                    if (!renewed) {
                        throw QueryResultsExpiredException("Result session expired. Re-run the query.")
                    }
                    renewAfter = now.plusSeconds(exportLeaseRenewalSeconds())
                }
            }
        }

    @Scheduled(fixedDelayString = "\${dwarvenpick.query.cleanup-interval-ms:30000}")
    fun cleanupExpiredResults() {
        val now = Instant.now()
        val ttlCutoff = now.minusSeconds(resultTtlSeconds())
        try {
            val expiredLeaseCount = repository.deleteExpiredExportLeases(now)
            updateMetrics(repository.storageSnapshot(ttlCutoff, now), now)
            if (properties.persistedResultExpiryDeleteEnabled) {
                val batch =
                    repository.expireIdleResults(
                        ttlCutoff = ttlCutoff,
                        now = now,
                        batchSize = properties.persistedResultExpiryBatchSize.coerceIn(1, 10_000),
                    )
                if (batch.expiredExecutionIds.isNotEmpty() || expiredLeaseCount > 0) {
                    logger.info(
                        "persisted_result_cleanup expiredExecutions={} expiredLeases={}",
                        batch.expiredExecutionIds.size,
                        expiredLeaseCount,
                    )
                }
                updateMetrics(repository.storageSnapshot(ttlCutoff, now), now)
            } else if (expiredLeaseCount > 0) {
                logger.info("persisted_result_cleanup expiredLeases={}", expiredLeaseCount)
            }
        } catch (ex: RuntimeException) {
            meterRegistry.counter("dwarvenpick.query.persisted.result.cleanup.failures").increment()
            logger.warn("persisted_result_cleanup failed", ex)
        }
    }

    private fun updateMetrics(
        snapshot: PersistedResultStorageSnapshot,
        observedAt: Instant,
    ) {
        persistedBytes.set(snapshot.bytes)
        persistedPages.set(snapshot.pageCount)
        expiryCandidates.set(snapshot.expiryCandidateCount)
        activeExportLeases.set(snapshot.activeExportLeaseCount)
        expiryLagSeconds.set(
            snapshot.oldestExpiryCandidateAt
                ?.let { candidateAt ->
                    (Duration.between(candidateAt, observedAt).seconds - resultTtlSeconds()).coerceAtLeast(0)
                }
                ?: 0,
        )
    }

    private fun resultTtlSeconds(): Long = properties.resultSessionTtlSeconds.coerceAtLeast(60)

    private fun exportLeaseSeconds(): Long = properties.resultExportLeaseSeconds.coerceAtLeast(60)

    private fun exportLeaseRenewalSeconds(): Long = (exportLeaseSeconds() / 2).coerceAtLeast(30)
}
