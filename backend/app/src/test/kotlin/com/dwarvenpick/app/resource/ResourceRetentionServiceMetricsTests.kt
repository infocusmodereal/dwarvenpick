package com.dwarvenpick.app.resource

import com.dwarvenpick.app.monitoring.MaintenanceMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant

class ResourceRetentionServiceMetricsTests {
    private val resourceRepository = mock(ResourceRepository::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val service =
        ResourceRetentionService(
            resourceRepository = resourceRepository,
            resourceProperties = ResourceProperties(),
            maintenanceMetrics = MaintenanceMetrics(meterRegistry),
        )

    @Test
    fun `successful resource cleanup records removed versions`() {
        `when`(resourceRepository.pruneVersions(anyInstant(), anyInt())).thenReturn(7)

        service.pruneResourceVersions()

        assertThat(cleanupCounter("success")).isEqualTo(1.0)
        assertThat(cleanupCounter("failure")).isEqualTo(0.0)
        assertThat(resourceRowCounter()).isEqualTo(7.0)
    }

    @Test
    fun `resource cleanup failure records failure without escaping scheduled boundary`() {
        doThrow(IllegalStateException("database unavailable"))
            .`when`(resourceRepository)
            .pruneVersions(anyInstant(), anyInt())

        service.pruneResourceVersions()

        assertThat(cleanupCounter("success")).isEqualTo(0.0)
        assertThat(cleanupCounter("failure")).isEqualTo(1.0)
        assertThat(lastFailureGauge()).isGreaterThan(0.0)
    }

    private fun cleanupCounter(outcome: String): Double =
        meterRegistry
            .get("dwarvenpick.retention.cleanup")
            .tags("scope", "resource", "outcome", outcome)
            .counter()
            .count()

    private fun resourceRowCounter(): Double =
        meterRegistry
            .get("dwarvenpick.retention.rows")
            .tags("scope", "resource", "store", "resource_versions", "action", "pruned")
            .counter()
            .count()

    private fun lastFailureGauge(): Double =
        meterRegistry
            .get("dwarvenpick.retention.cleanup.last.failure.epoch.seconds")
            .tag("scope", "resource")
            .gauge()
            .value()

    private fun anyInstant(): Instant {
        any(Instant::class.java)
        return Instant.EPOCH
    }
}
