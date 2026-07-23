package com.dwarvenpick.app.query

import com.dwarvenpick.app.runtime.ApplicationInstanceId
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

@Component
class QueryLifecycleMetrics(
    private val queryRuntimeRepository: QueryRuntimeRepository,
    private val queryExecutionProperties: QueryExecutionProperties,
    private val applicationInstanceId: ApplicationInstanceId,
    meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(QueryLifecycleMetrics::class.java)
    private val localOwnedCount = AtomicLong(0)
    private val oldestHeartbeatAgeSeconds = AtomicLong(0)
    private val staleCount = AtomicLong(0)
    private val pendingCounts = RemoteQueryControlAction.entries.associateWith { AtomicLong(0) }
    private val oldestPendingAgeSeconds = RemoteQueryControlAction.entries.associateWith { AtomicLong(0) }
    private val requestCounters =
        RemoteQueryControlAction.entries.associateWith { action ->
            Counter
                .builder("dwarvenpick.query.remote.control.requests")
                .description("Remote query control requests routed through the shared metadata database")
                .tag("action", action.tagValue)
                .register(meterRegistry)
        }
    private val observationTimers =
        RemoteQueryControlAction.entries.associateWith { action ->
            Timer
                .builder("dwarvenpick.query.remote.control.latency")
                .description("Time from a remote control request until the owning backend observes it")
                .tag("action", action.tagValue)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(2))
                .register(meterRegistry)
        }

    init {
        gauge(
            meterRegistry = meterRegistry,
            name = "dwarvenpick.query.active.owned",
            description = "Active query executions owned by this backend instance",
            state = localOwnedCount,
            tags = mapOf("scope" to "local"),
        )
        gauge(
            meterRegistry = meterRegistry,
            name = "dwarvenpick.query.active.heartbeat.oldest.age.seconds",
            description = "Age of the oldest active execution owner heartbeat",
            state = oldestHeartbeatAgeSeconds,
            baseUnit = "seconds",
        )
        gauge(
            meterRegistry = meterRegistry,
            name = "dwarvenpick.query.active.heartbeat.stale",
            description = "Active executions whose owner heartbeat exceeded the stale cutoff",
            state = staleCount,
        )
        RemoteQueryControlAction.entries.forEach { action ->
            gauge(
                meterRegistry = meterRegistry,
                name = "dwarvenpick.query.remote.control.pending",
                description = "Remote query control requests not yet observed by the owning backend",
                state = pendingCounts.getValue(action),
                tags = mapOf("action" to action.tagValue),
            )
            gauge(
                meterRegistry = meterRegistry,
                name = "dwarvenpick.query.remote.control.oldest.age.seconds",
                description = "Age of the oldest unobserved remote query control request",
                state = oldestPendingAgeSeconds.getValue(action),
                baseUnit = "seconds",
                tags = mapOf("action" to action.tagValue),
            )
        }
    }

    @Scheduled(
        fixedDelayString = "\${dwarvenpick.query.lifecycle-metrics-refresh-interval-ms:10000}",
        scheduler = "queryLifecycleTaskScheduler",
    )
    fun refresh() {
        runCatching {
            val staleCutoff =
                Instant.now().minusSeconds(queryExecutionProperties.activeExecutionStaleSeconds.coerceAtLeast(60))
            QueryLifecycleSnapshot.from(
                queryRuntimeRepository.loadLifecycleAggregate(
                    ownerInstanceId = applicationInstanceId.value,
                    staleCutoff = staleCutoff,
                ),
            )
        }.onSuccess(::applySnapshot)
            .onFailure { exception ->
                logger.warn("query_lifecycle_metrics refresh failed; retaining last known values", exception)
            }
    }

    fun recordRemoteRequest(action: RemoteQueryControlAction) {
        requestCounters.getValue(action).increment()
    }

    fun recordRemoteObservation(request: RemoteQueryControlRequest) {
        val latency = Duration.between(request.requestedAt, request.observedAt)
        observationTimers.getValue(request.action).record(if (latency.isNegative) Duration.ZERO else latency)
    }

    private fun applySnapshot(snapshot: QueryLifecycleSnapshot) {
        localOwnedCount.set(snapshot.localOwnedCount)
        oldestHeartbeatAgeSeconds.set(snapshot.oldestHeartbeatAgeSeconds)
        staleCount.set(snapshot.staleCount)
        RemoteQueryControlAction.entries.forEach { action ->
            pendingCounts.getValue(action).set(snapshot.pendingCountByAction.getValue(action))
            oldestPendingAgeSeconds
                .getValue(action)
                .set(snapshot.oldestPendingAgeSecondsByAction.getValue(action))
        }
    }

    private fun gauge(
        meterRegistry: MeterRegistry,
        name: String,
        description: String,
        state: AtomicLong,
        baseUnit: String? = null,
        tags: Map<String, String> = emptyMap(),
    ) {
        val builder =
            Gauge
                .builder(name, state) { value -> value.get().toDouble() }
                .description(description)
        if (baseUnit != null) {
            builder.baseUnit(baseUnit)
        }
        tags.forEach { (key, value) -> builder.tag(key, value) }
        builder.register(meterRegistry)
    }
}
