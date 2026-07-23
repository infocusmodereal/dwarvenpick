package com.dwarvenpick.app.query

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
class QueryLifecycleMetricsIntegrationTests {
    @Autowired
    private lateinit var queryLifecycleMetrics: QueryLifecycleMetrics

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `actuator prometheus exposes exact query lifecycle metric names and bounded tags`() {
        val requestedAt = Instant.now().minusSeconds(2)
        queryLifecycleMetrics.refresh()
        queryLifecycleMetrics.recordRemoteRequest(RemoteQueryControlAction.CANCEL)
        queryLifecycleMetrics.recordRemoteObservation(
            RemoteQueryControlRequest(
                executionId = "prometheus-control-execution",
                action = RemoteQueryControlAction.CANCEL,
                requestedAt = requestedAt,
                observedAt = requestedAt.plusSeconds(1),
            ),
        )

        val response =
            mockMvc
                .perform(get("/actuator/prometheus"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        assertThat(response).contains("dwarvenpick_query_active_owned{scope=\"local\"}")
        assertThat(response).contains("dwarvenpick_query_active_heartbeat_oldest_age_seconds")
        assertThat(response).contains("dwarvenpick_query_active_heartbeat_stale")
        assertThat(response).contains("dwarvenpick_query_remote_control_pending{action=\"cancel\"}")
        assertThat(response).contains("dwarvenpick_query_remote_control_oldest_age_seconds{action=\"cancel\"}")
        assertThat(response).contains("dwarvenpick_query_remote_control_requests_total{action=\"cancel\"}")
        assertThat(response).contains("dwarvenpick_query_remote_control_latency_seconds_count{action=\"cancel\"}")
        assertThat(response).doesNotContain("executionId=", "actor=", "datasourceId=", "ownerInstanceId=")
    }
}
