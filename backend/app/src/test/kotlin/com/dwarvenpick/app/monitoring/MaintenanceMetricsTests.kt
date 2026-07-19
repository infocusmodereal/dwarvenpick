package com.dwarvenpick.app.monitoring

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class MaintenanceMetricsTests {
    private val meterRegistry = SimpleMeterRegistry()
    private val metrics = MaintenanceMetrics(meterRegistry)

    @Test
    fun `audit append metrics record success and failure`() {
        metrics.recordAuditAppend(MaintenanceOutcome.SUCCESS)
        metrics.recordAuditAppend(MaintenanceOutcome.FAILURE)

        assertThat(counter("dwarvenpick.audit.append", "outcome", "success")).isEqualTo(1.0)
        assertThat(counter("dwarvenpick.audit.append", "outcome", "failure")).isEqualTo(1.0)
    }

    @Test
    fun `cleanup metrics retain epoch second gauges and bounded tags`() {
        val completedAt = Instant.ofEpochSecond(1_750_000_123)
        metrics.recordCleanup(RetentionScope.QUERY, MaintenanceOutcome.SUCCESS, completedAt)
        metrics.recordCleanup(RetentionScope.RESOURCE, MaintenanceOutcome.FAILURE, completedAt.plusSeconds(7))

        assertThat(
            gauge(
                "dwarvenpick.retention.cleanup.last.success.epoch.seconds",
                "scope",
                "query",
            ),
        ).isEqualTo(1_750_000_123.0)
        assertThat(
            gauge(
                "dwarvenpick.retention.cleanup.last.failure.epoch.seconds",
                "scope",
                "resource",
            ),
        ).isEqualTo(1_750_000_130.0)
        assertThat(counter("dwarvenpick.retention.cleanup", "scope", "query", "outcome", "success"))
            .isEqualTo(1.0)
        assertThat(counter("dwarvenpick.retention.cleanup", "scope", "resource", "outcome", "failure"))
            .isEqualTo(1.0)
    }

    @Test
    fun `retention row counters preserve exact store and action dimensions`() {
        metrics.recordRows(RetentionStore.QUERY_HISTORY, RetentionAction.PRUNED, 2)
        metrics.recordRows(RetentionStore.QUERY_RUNTIME, RetentionAction.REDACTED, 3)
        metrics.recordRows(RetentionStore.RESOURCE_VERSIONS, RetentionAction.PRUNED, 4)

        assertThat(rowCounter("query", "query_history", "pruned")).isEqualTo(2.0)
        assertThat(rowCounter("query", "query_runtime", "redacted")).isEqualTo(3.0)
        assertThat(rowCounter("resource", "resource_versions", "pruned")).isEqualTo(4.0)
        assertThat(
            meterRegistry
                .find("dwarvenpick.retention.rows")
                .counters()
                .map { counter -> counter.id.tags.associate { tag -> tag.key to tag.value } },
        ).containsExactlyInAnyOrder(
            mapOf("scope" to "query", "store" to "query_history", "action" to "pruned"),
            mapOf("scope" to "query", "store" to "query_history", "action" to "redacted"),
            mapOf("scope" to "query", "store" to "query_runtime", "action" to "pruned"),
            mapOf("scope" to "query", "store" to "query_runtime", "action" to "redacted"),
            mapOf("scope" to "query", "store" to "audit_events", "action" to "pruned"),
            mapOf("scope" to "resource", "store" to "resource_versions", "action" to "pruned"),
        )
    }

    @Test
    fun `negative retention row counts are rejected`() {
        assertThatThrownBy {
            metrics.recordRows(RetentionStore.AUDIT_EVENTS, RetentionAction.PRUNED, -1)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Retention row count must not be negative.")
    }

    private fun counter(
        name: String,
        vararg tags: String,
    ): Double =
        meterRegistry
            .get(name)
            .tags(*tags)
            .counter()
            .count()

    private fun gauge(
        name: String,
        vararg tags: String,
    ): Double =
        meterRegistry
            .get(name)
            .tags(*tags)
            .gauge()
            .value()

    private fun rowCounter(
        scope: String,
        store: String,
        action: String,
    ): Double =
        meterRegistry
            .get("dwarvenpick.retention.rows")
            .tags("scope", scope, "store", store, "action", action)
            .counter()
            .count()
}
