package com.dwarvenpick.app.query

import com.dwarvenpick.app.runtime.ApplicationInstanceId
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant

class QueryLifecycleMetricsTests {
    private val repository = mock(QueryRuntimeRepository::class.java)
    private val instanceId = mock(ApplicationInstanceId::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val metrics =
        QueryLifecycleMetrics(
            queryRuntimeRepository = repository,
            queryExecutionProperties = QueryExecutionProperties(activeExecutionStaleSeconds = 120),
            applicationInstanceId = instanceId,
            meterRegistry = meterRegistry,
        )

    @Test
    fun `refresh publishes bounded ownership heartbeat and pending gauges`() {
        val observedAt = Instant.parse("2026-07-23T01:00:00Z")
        `when`(instanceId.value).thenReturn("backend-1")
        `when`(repository.loadLifecycleAggregate(eqString("backend-1"), anyInstant()))
            .thenReturn(
                QueryLifecycleAggregate(
                    observedAt = observedAt,
                    activeCount = 3,
                    localOwnedCount = 2,
                    oldestHeartbeatAt = observedAt.minusSeconds(95),
                    staleCount = 1,
                    pendingCancelCount = 1,
                    oldestPendingCancelAt = observedAt.minusSeconds(12),
                    pendingKillCount = 0,
                    oldestPendingKillAt = null,
                ),
            )

        metrics.refresh()

        assertThat(gauge("dwarvenpick.query.active.owned", "scope", "local")).isEqualTo(2.0)
        assertThat(gauge("dwarvenpick.query.active.heartbeat.oldest.age.seconds")).isEqualTo(95.0)
        assertThat(gauge("dwarvenpick.query.active.heartbeat.stale")).isEqualTo(1.0)
        assertThat(gauge("dwarvenpick.query.remote.control.pending", "action", "cancel")).isEqualTo(1.0)
        assertThat(gauge("dwarvenpick.query.remote.control.oldest.age.seconds", "action", "cancel"))
            .isEqualTo(12.0)
        verify(repository).loadLifecycleAggregate(eqString("backend-1"), anyInstant())
    }

    @Test
    fun `remote request and observation metrics use bounded action labels`() {
        val requestedAt = Instant.parse("2026-07-23T01:00:00Z")
        metrics.recordRemoteRequest(RemoteQueryControlAction.KILL)
        metrics.recordRemoteObservation(
            RemoteQueryControlRequest(
                executionId = "execution-1",
                action = RemoteQueryControlAction.KILL,
                requestedAt = requestedAt,
                observedAt = requestedAt.plusSeconds(3),
            ),
        )

        assertThat(
            meterRegistry
                .get("dwarvenpick.query.remote.control.requests")
                .tag("action", "kill")
                .counter()
                .count(),
        ).isEqualTo(1.0)
        val timer =
            meterRegistry
                .get("dwarvenpick.query.remote.control.latency")
                .tag("action", "kill")
                .timer()
        assertThat(timer.count()).isEqualTo(1)
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(3.0)
        assertThat(
            meterRegistry
                .find("dwarvenpick.query.remote.control.latency")
                .timers()
                .flatMap { item -> item.id.tags }
                .filter { tag -> tag.key == "action" }
                .map { tag -> tag.value },
        ).containsExactlyInAnyOrder("cancel", "kill")
    }

    @Test
    fun `refresh failure retains last known gauges`() {
        val observedAt = Instant.parse("2026-07-23T01:00:00Z")
        `when`(instanceId.value).thenReturn("backend-1")
        `when`(repository.loadLifecycleAggregate(anyString(), anyInstant()))
            .thenReturn(
                QueryLifecycleAggregate(
                    observedAt = observedAt,
                    activeCount = 1,
                    localOwnedCount = 1,
                    oldestHeartbeatAt = observedAt.minusSeconds(20),
                    staleCount = 0,
                    pendingCancelCount = 0,
                    oldestPendingCancelAt = null,
                    pendingKillCount = 0,
                    oldestPendingKillAt = null,
                ),
            ).thenThrow(IllegalStateException("metadata database unavailable"))

        metrics.refresh()
        metrics.refresh()

        assertThat(gauge("dwarvenpick.query.active.owned", "scope", "local")).isEqualTo(1.0)
        assertThat(gauge("dwarvenpick.query.active.heartbeat.oldest.age.seconds")).isEqualTo(20.0)
    }

    private fun gauge(
        name: String,
        vararg tags: String,
    ): Double =
        meterRegistry
            .get(name)
            .tags(*tags)
            .gauge()
            .value()

    private fun anyInstant(): Instant {
        org.mockito.ArgumentMatchers.any(Instant::class.java)
        return Instant.EPOCH
    }

    private fun anyString(): String {
        org.mockito.ArgumentMatchers.anyString()
        return ""
    }

    private fun eqString(value: String): String {
        org.mockito.ArgumentMatchers.eq(value)
        return value
    }
}
