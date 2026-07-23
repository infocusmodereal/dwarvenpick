package com.dwarvenpick.app.query

import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class RemoteQueryControlAction(
    val tagValue: String,
) {
    CANCEL("cancel"),
    KILL("kill"),
}

data class RemoteQueryControlRequest(
    val executionId: String,
    val action: RemoteQueryControlAction,
    val requestedAt: Instant,
    val observedAt: Instant,
)

internal class LegacyCancelCheckGate(
    intervalMillis: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMillis.coerceAtLeast(100))
    private val nextCheckNanos = AtomicLong(Long.MIN_VALUE)

    fun shouldCheck(): Boolean {
        val now = nanoTime()
        while (true) {
            val next = nextCheckNanos.get()
            if (next != Long.MIN_VALUE && now < next) {
                return false
            }
            if (nextCheckNanos.compareAndSet(next, now + intervalNanos)) {
                return true
            }
        }
    }
}

data class QueryLifecycleAggregate(
    val observedAt: Instant,
    val activeCount: Long,
    val localOwnedCount: Long,
    val oldestHeartbeatAt: Instant?,
    val staleCount: Long,
    val pendingCancelCount: Long,
    val oldestPendingCancelAt: Instant?,
    val pendingKillCount: Long,
    val oldestPendingKillAt: Instant?,
)

data class QueryLifecycleSnapshot(
    val activeCount: Long,
    val localOwnedCount: Long,
    val oldestHeartbeatAgeSeconds: Long,
    val staleCount: Long,
    val pendingCountByAction: Map<RemoteQueryControlAction, Long>,
    val oldestPendingAgeSecondsByAction: Map<RemoteQueryControlAction, Long>,
) {
    companion object {
        fun from(aggregate: QueryLifecycleAggregate): QueryLifecycleSnapshot =
            QueryLifecycleSnapshot(
                activeCount = aggregate.activeCount,
                localOwnedCount = aggregate.localOwnedCount,
                oldestHeartbeatAgeSeconds = ageSeconds(aggregate.oldestHeartbeatAt, aggregate.observedAt),
                staleCount = aggregate.staleCount,
                pendingCountByAction =
                    mapOf(
                        RemoteQueryControlAction.CANCEL to aggregate.pendingCancelCount,
                        RemoteQueryControlAction.KILL to aggregate.pendingKillCount,
                    ),
                oldestPendingAgeSecondsByAction =
                    mapOf(
                        RemoteQueryControlAction.CANCEL to
                            ageSeconds(aggregate.oldestPendingCancelAt, aggregate.observedAt),
                        RemoteQueryControlAction.KILL to
                            ageSeconds(aggregate.oldestPendingKillAt, aggregate.observedAt),
                    ),
            )

        private fun ageSeconds(
            timestamp: Instant?,
            observedAt: Instant,
        ): Long =
            timestamp
                ?.let { value -> Duration.between(value, observedAt).seconds.coerceAtLeast(0) }
                ?: 0
    }
}
