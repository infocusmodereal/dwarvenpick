package com.dwarvenpick.app.query

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant

class PersistedQueryResultLifecycleServiceTests {
    @Test
    fun `observation mode reports database storage without deleting candidates`() {
        val repository = mock(PersistedResultLifecycleRepository::class.java)
        val meterRegistry = SimpleMeterRegistry()
        val service =
            PersistedQueryResultLifecycleService(
                repository = repository,
                properties =
                    QueryExecutionProperties(
                        resultSessionTtlSeconds = 600,
                        persistedResultExpiryDeleteEnabled = false,
                    ),
                meterRegistry = meterRegistry,
            )
        service.registerMetrics()
        `when`(repository.storageSnapshot(anyValue(), anyValue()))
            .thenReturn(
                PersistedResultStorageSnapshot(
                    bytes = 1024,
                    pageCount = 3,
                    expiryCandidateCount = 2,
                    oldestExpiryCandidateAt = Instant.now().minusSeconds(700),
                    activeExportLeaseCount = 1,
                ),
            )

        service.cleanupExpiredResults()

        verify(repository, never()).expireIdleResults(anyValue(), anyValue(), anyInt())
        assertThat(meterRegistry.get("dwarvenpick.query.persisted.result.bytes").gauge().value()).isEqualTo(1024.0)
        assertThat(meterRegistry.get("dwarvenpick.query.persisted.result.pages").gauge().value()).isEqualTo(3.0)
        assertThat(meterRegistry.get("dwarvenpick.query.persisted.result.expiry.candidates").gauge().value()).isEqualTo(2.0)
        assertThat(meterRegistry.get("dwarvenpick.query.persisted.result.export.leases").gauge().value()).isEqualTo(1.0)
        assertThat(meterRegistry.get("dwarvenpick.query.persisted.result.expiry.deletion.enabled").gauge().value())
            .isZero()
    }

    @Test
    fun `cleanup failures increment a stable counter`() {
        val repository = mock(PersistedResultLifecycleRepository::class.java)
        val meterRegistry = SimpleMeterRegistry()
        val service =
            PersistedQueryResultLifecycleService(
                repository = repository,
                properties = QueryExecutionProperties(),
                meterRegistry = meterRegistry,
            )
        `when`(repository.storageSnapshot(anyValue(), anyValue()))
            .thenThrow(IllegalStateException("metadata unavailable"))

        service.cleanupExpiredResults()

        assertThat(meterRegistry.get("dwarvenpick.query.persisted.result.cleanup.failures").counter().count())
            .isEqualTo(1.0)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        ArgumentMatchers.any<T>()
        return null as T
    }
}
