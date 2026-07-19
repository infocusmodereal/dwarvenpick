package com.dwarvenpick.app.query

import com.dwarvenpick.app.auth.AuthAuditEventStore
import com.dwarvenpick.app.monitoring.MaintenanceMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant

class QueryRetentionServiceMetricsTests {
    private val queryExecutionManager = mock(QueryExecutionManager::class.java)
    private val authAuditEventStore = mock(AuthAuditEventStore::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val service =
        QueryRetentionService(
            queryExecutionManager = queryExecutionManager,
            authAuditEventStore = authAuditEventStore,
            queryExecutionProperties = QueryExecutionProperties(),
            maintenanceMetrics = MaintenanceMetrics(meterRegistry),
        )

    @Test
    fun `successful cleanup records run and all affected rows`() {
        `when`(queryExecutionManager.pruneHistoryOlderThan(anyInstant())).thenReturn(2)
        `when`(queryExecutionManager.pruneRuntimeOlderThan(anyInstant())).thenReturn(3)
        `when`(authAuditEventStore.pruneOlderThan(anyInstant())).thenReturn(4)
        `when`(queryExecutionManager.redactHistoryQueryTextOlderThan(anyInstant())).thenReturn(5)
        `when`(queryExecutionManager.redactRuntimeQueryTextOlderThan(anyInstant())).thenReturn(6)

        service.pruneHistoryAndAudit()

        assertThat(cleanupCounter("success")).isEqualTo(1.0)
        assertThat(cleanupCounter("failure")).isEqualTo(0.0)
        assertThat(rowCounter("query_history", "pruned")).isEqualTo(2.0)
        assertThat(rowCounter("query_runtime", "pruned")).isEqualTo(3.0)
        assertThat(rowCounter("audit_events", "pruned")).isEqualTo(4.0)
        assertThat(rowCounter("query_history", "redacted")).isEqualTo(5.0)
        assertThat(rowCounter("query_runtime", "redacted")).isEqualTo(6.0)
    }

    @Test
    fun `zero row cleanup still refreshes last success`() {
        val startupTimestamp = lastSuccessGauge()

        service.pruneHistoryAndAudit()

        assertThat(cleanupCounter("success")).isEqualTo(1.0)
        assertThat(lastSuccessGauge()).isGreaterThanOrEqualTo(startupTimestamp)
    }

    @Test
    fun `partial cleanup failure preserves completed row counts and records failure`() {
        `when`(queryExecutionManager.pruneHistoryOlderThan(anyInstant())).thenReturn(2)
        `when`(queryExecutionManager.pruneRuntimeOlderThan(anyInstant())).thenReturn(3)
        doThrow(IllegalStateException("database unavailable"))
            .`when`(authAuditEventStore)
            .pruneOlderThan(anyInstant())

        service.pruneHistoryAndAudit()

        assertThat(cleanupCounter("success")).isEqualTo(0.0)
        assertThat(cleanupCounter("failure")).isEqualTo(1.0)
        assertThat(rowCounter("query_history", "pruned")).isEqualTo(2.0)
        assertThat(rowCounter("query_runtime", "pruned")).isEqualTo(3.0)
        assertThat(lastFailureGauge()).isGreaterThan(0.0)
    }

    private fun cleanupCounter(outcome: String): Double =
        meterRegistry
            .get("dwarvenpick.retention.cleanup")
            .tags("scope", "query", "outcome", outcome)
            .counter()
            .count()

    private fun rowCounter(
        store: String,
        action: String,
    ): Double =
        meterRegistry
            .get("dwarvenpick.retention.rows")
            .tags("scope", "query", "store", store, "action", action)
            .counter()
            .count()

    private fun lastSuccessGauge(): Double =
        meterRegistry
            .get("dwarvenpick.retention.cleanup.last.success.epoch.seconds")
            .tag("scope", "query")
            .gauge()
            .value()

    private fun lastFailureGauge(): Double =
        meterRegistry
            .get("dwarvenpick.retention.cleanup.last.failure.epoch.seconds")
            .tag("scope", "query")
            .gauge()
            .value()

    private fun anyInstant(): Instant {
        any(Instant::class.java)
        return Instant.EPOCH
    }
}
