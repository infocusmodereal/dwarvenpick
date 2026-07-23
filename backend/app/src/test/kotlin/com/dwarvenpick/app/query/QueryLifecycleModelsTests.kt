package com.dwarvenpick.app.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class QueryLifecycleModelsTests {
    @Test
    fun `snapshot derives bounded non-negative ages from database timestamps`() {
        val observedAt = Instant.parse("2026-07-23T01:00:00Z")
        val snapshot =
            QueryLifecycleSnapshot.from(
                QueryLifecycleAggregate(
                    observedAt = observedAt,
                    activeCount = 4,
                    localOwnedCount = 2,
                    oldestHeartbeatAt = observedAt.minusSeconds(95),
                    staleCount = 1,
                    pendingCancelCount = 2,
                    oldestPendingCancelAt = observedAt.minusSeconds(12),
                    pendingKillCount = 1,
                    oldestPendingKillAt = observedAt.plusSeconds(5),
                ),
            )

        assertThat(snapshot.activeCount).isEqualTo(4)
        assertThat(snapshot.localOwnedCount).isEqualTo(2)
        assertThat(snapshot.oldestHeartbeatAgeSeconds).isEqualTo(95)
        assertThat(snapshot.staleCount).isEqualTo(1)
        assertThat(snapshot.pendingCountByAction)
            .containsEntry(RemoteQueryControlAction.CANCEL, 2)
            .containsEntry(RemoteQueryControlAction.KILL, 1)
        assertThat(snapshot.oldestPendingAgeSecondsByAction)
            .containsEntry(RemoteQueryControlAction.CANCEL, 12)
            .containsEntry(RemoteQueryControlAction.KILL, 0)
    }

    @Test
    fun `empty lifecycle timestamps produce zero ages`() {
        val snapshot =
            QueryLifecycleSnapshot.from(
                QueryLifecycleAggregate(
                    observedAt = Instant.parse("2026-07-23T01:00:00Z"),
                    activeCount = 0,
                    localOwnedCount = 0,
                    oldestHeartbeatAt = null,
                    staleCount = 0,
                    pendingCancelCount = 0,
                    oldestPendingCancelAt = null,
                    pendingKillCount = 0,
                    oldestPendingKillAt = null,
                ),
            )

        assertThat(snapshot.oldestHeartbeatAgeSeconds).isZero()
        assertThat(snapshot.oldestPendingAgeSecondsByAction.values).containsOnly(0)
    }

    @Test
    fun `legacy cancel checks are bounded by the remote control poll interval`() {
        val nowNanos = AtomicLong(1_000_000_000)
        val gate = LegacyCancelCheckGate(intervalMillis = 1_000, nanoTime = nowNanos::get)

        assertThat(gate.shouldCheck()).isTrue()
        repeat(5_000) {
            assertThat(gate.shouldCheck()).isFalse()
        }

        nowNanos.addAndGet(999_000_000)
        assertThat(gate.shouldCheck()).isFalse()
        nowNanos.addAndGet(1_000_000)
        assertThat(gate.shouldCheck()).isTrue()
    }
}
