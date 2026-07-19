package com.dwarvenpick.app.monitoring

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

enum class MaintenanceOutcome(
    val tagValue: String,
) {
    SUCCESS("success"),
    FAILURE("failure"),
}

enum class RetentionScope(
    val tagValue: String,
) {
    QUERY("query"),
    RESOURCE("resource"),
}

enum class RetentionStore(
    val scope: RetentionScope,
    val tagValue: String,
) {
    QUERY_HISTORY(RetentionScope.QUERY, "query_history"),
    QUERY_RUNTIME(RetentionScope.QUERY, "query_runtime"),
    AUDIT_EVENTS(RetentionScope.QUERY, "audit_events"),
    RESOURCE_VERSIONS(RetentionScope.RESOURCE, "resource_versions"),
}

enum class RetentionAction(
    val tagValue: String,
) {
    PRUNED("pruned"),
    REDACTED("redacted"),
}

@Component
class MaintenanceMetrics(
    meterRegistry: MeterRegistry,
) {
    private val auditAppendCounters =
        MaintenanceOutcome.entries.associateWith { outcome ->
            Counter
                .builder("dwarvenpick.audit.append")
                .description("Audit event persistence attempts")
                .tag("outcome", outcome.tagValue)
                .register(meterRegistry)
        }
    private val cleanupCounters =
        RetentionScope.entries
            .flatMap { scope ->
                MaintenanceOutcome.entries.map { outcome ->
                    (scope to outcome) to
                        Counter
                            .builder("dwarvenpick.retention.cleanup")
                            .description("Retention cleanup runs")
                            .tag("scope", scope.tagValue)
                            .tag("outcome", outcome.tagValue)
                            .register(meterRegistry)
                }
            }.toMap()
    private val retentionRowCounters =
        validRowCounterKeys().associateWith { (store, action) ->
            Counter
                .builder("dwarvenpick.retention.rows")
                .description("Rows pruned or redacted by retention cleanup")
                .tag("scope", store.scope.tagValue)
                .tag("store", store.tagValue)
                .tag("action", action.tagValue)
                .register(meterRegistry)
        }
    private val startupEpochSeconds = Instant.now().epochSecond
    private val lastSuccessEpochSeconds =
        RetentionScope.entries.associateWith { AtomicLong(startupEpochSeconds) }
    private val lastFailureEpochSeconds =
        RetentionScope.entries.associateWith { AtomicLong(0) }

    init {
        RetentionScope.entries.forEach { scope ->
            registerTimestampGauge(
                meterRegistry = meterRegistry,
                name = "dwarvenpick.retention.cleanup.last.success.epoch.seconds",
                description = "Epoch seconds of the last successful retention cleanup run",
                scope = scope,
                state = lastSuccessEpochSeconds.getValue(scope),
            )
            registerTimestampGauge(
                meterRegistry = meterRegistry,
                name = "dwarvenpick.retention.cleanup.last.failure.epoch.seconds",
                description = "Epoch seconds of the last failed retention cleanup run",
                scope = scope,
                state = lastFailureEpochSeconds.getValue(scope),
            )
        }
    }

    fun recordAuditAppend(outcome: MaintenanceOutcome) {
        auditAppendCounters.getValue(outcome).increment()
    }

    fun recordCleanup(
        scope: RetentionScope,
        outcome: MaintenanceOutcome,
        timestamp: Instant,
    ) {
        cleanupCounters.getValue(scope to outcome).increment()
        val timestampState =
            when (outcome) {
                MaintenanceOutcome.SUCCESS -> lastSuccessEpochSeconds
                MaintenanceOutcome.FAILURE -> lastFailureEpochSeconds
            }
        timestampState.getValue(scope).set(timestamp.epochSecond)
    }

    fun recordRows(
        store: RetentionStore,
        action: RetentionAction,
        count: Int,
    ) {
        require(count >= 0) { "Retention row count must not be negative." }
        if (count == 0) {
            return
        }
        retentionRowCounters.getValue(store to action).increment(count.toDouble())
    }

    private fun registerTimestampGauge(
        meterRegistry: MeterRegistry,
        name: String,
        description: String,
        scope: RetentionScope,
        state: AtomicLong,
    ) {
        Gauge
            .builder(name, state) { value -> value.get().toDouble() }
            .description(description)
            .tag("scope", scope.tagValue)
            .baseUnit("seconds")
            .register(meterRegistry)
    }

    private fun validRowCounterKeys(): List<Pair<RetentionStore, RetentionAction>> =
        listOf(
            RetentionStore.QUERY_HISTORY to RetentionAction.PRUNED,
            RetentionStore.QUERY_HISTORY to RetentionAction.REDACTED,
            RetentionStore.QUERY_RUNTIME to RetentionAction.PRUNED,
            RetentionStore.QUERY_RUNTIME to RetentionAction.REDACTED,
            RetentionStore.AUDIT_EVENTS to RetentionAction.PRUNED,
            RetentionStore.RESOURCE_VERSIONS to RetentionAction.PRUNED,
        )
}
