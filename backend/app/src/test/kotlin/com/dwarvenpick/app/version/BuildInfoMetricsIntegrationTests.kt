package com.dwarvenpick.app.version

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "management.endpoint.prometheus.enabled=true",
        "management.endpoints.web.exposure.include=health,info,prometheus",
        "management.prometheus.metrics.export.enabled=true",
        "DWARVENPICK_SOURCE_SHA=integration-sha",
    ],
)
@AutoConfigureMockMvc
class BuildInfoMetricsIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `actuator prometheus exposes build info heartbeat`() {
        val response =
            mockMvc
                .perform(get("/actuator/prometheus"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        val buildInfo = response.lineSequence().single { it.startsWith("dwarvenpick_build_info{") }
        assertThat(buildInfo).contains("source_sha=\"integration-sha\"")
        assertThat(buildInfo.substringAfterLast(' ')).isIn("1", "1.0")
    }
}
