package com.dwarvenpick.app.auth

import com.dwarvenpick.app.monitoring.MaintenanceMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class AuthAuditEventStoreMetricsTests {
    private val repository = mock(AuthAuditEventRepository::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val store = AuthAuditEventStore(repository, MaintenanceMetrics(meterRegistry))
    private val event =
        AuthAuditEvent(
            type = "query.execute",
            actor = "analyst",
            outcome = "success",
            ipAddress = "127.0.0.1",
            details = mapOf("executionId" to "execution-1"),
        )

    @Test
    fun `successful audit append records success`() {
        store.append(event)

        verify(repository).append(event)
        assertThat(auditCounter("success")).isEqualTo(1.0)
        assertThat(auditCounter("failure")).isEqualTo(0.0)
    }

    @Test
    fun `failed audit append records failure and preserves fail closed behavior`() {
        doThrow(IllegalStateException("database unavailable")).`when`(repository).append(event)

        assertThatThrownBy { store.append(event) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("database unavailable")
        assertThat(auditCounter("success")).isEqualTo(0.0)
        assertThat(auditCounter("failure")).isEqualTo(1.0)
    }

    private fun auditCounter(outcome: String): Double =
        meterRegistry
            .get("dwarvenpick.audit.append")
            .tag("outcome", outcome)
            .counter()
            .count()
}
