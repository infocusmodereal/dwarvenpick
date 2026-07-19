package com.dwarvenpick.app.monitoring

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@SpringBootTest(
    properties = [
        "management.endpoint.prometheus.enabled=true",
        "management.endpoints.web.exposure.include=health,info,prometheus",
        "management.prometheus.metrics.export.enabled=true",
    ],
)
@AutoConfigureMockMvc
class MaintenanceMetricsIntegrationTests {
    @Autowired
    private lateinit var maintenanceMetrics: MaintenanceMetrics

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `actuator prometheus exposes exact maintenance metric names and tags`() {
        maintenanceMetrics.recordAuditAppend(MaintenanceOutcome.FAILURE)
        maintenanceMetrics.recordCleanup(
            RetentionScope.QUERY,
            MaintenanceOutcome.FAILURE,
            Instant.ofEpochSecond(1_750_000_000),
        )
        maintenanceMetrics.recordRows(RetentionStore.AUDIT_EVENTS, RetentionAction.PRUNED, 2)

        val response =
            mockMvc
                .perform(get("/actuator/prometheus"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        assertThat(response).contains("dwarvenpick_audit_append_total{outcome=\"failure\"}")
        assertThat(response).contains(
            "dwarvenpick_retention_cleanup_total{outcome=\"failure\",scope=\"query\"}",
        )
        assertThat(response).contains(
            "dwarvenpick_retention_cleanup_last_success_epoch_seconds{scope=\"query\"}",
        )
        assertThat(response).contains(
            "dwarvenpick_retention_cleanup_last_failure_epoch_seconds{scope=\"query\"}",
        )
        assertThat(response).contains(
            "dwarvenpick_retention_rows_total{action=\"pruned\",scope=\"query\",store=\"audit_events\"}",
        )
    }
}
