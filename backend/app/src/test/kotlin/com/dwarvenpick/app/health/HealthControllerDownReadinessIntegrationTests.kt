package com.dwarvenpick.app.health

import com.dwarvenpick.app.auth.AuthAuditEventStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "management.endpoint.health.group.readiness.include=readinessState,syntheticReadiness",
    ],
)
@AutoConfigureMockMvc
@Import(DownReadinessConfiguration::class)
class HealthControllerDownReadinessIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var authAuditEventStore: AuthAuditEventStore

    @BeforeEach
    fun clearAuditEvents() {
        authAuditEventStore.clear()
    }

    @Test
    fun `api health mirrors a down readiness state without exposing details`() {
        mockMvc
            .perform(get("/actuator/health/readiness"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.status").value("DOWN"))
            .andExpect(jsonPath("$.components").doesNotExist())
            .andExpect(jsonPath("$.details").doesNotExist())

        mockMvc
            .perform(get("/api/health"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.service").value("dwarvenpick-backend"))
            .andExpect(jsonPath("$.status").value("DOWN"))
            .andExpect(jsonPath("$.timestamp").isString())
            .andExpect(jsonPath("$.components").doesNotExist())
            .andExpect(jsonPath("$.details").doesNotExist())

        assertThat(authAuditEventStore.snapshot()).isEmpty()
    }

    @Test
    fun `liveness stays up when a readiness contributor is down`() {
        mockMvc
            .perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))

        assertThat(authAuditEventStore.snapshot()).isEmpty()
    }
}

@TestConfiguration(proxyBeanMethods = false)
class DownReadinessConfiguration {
    @Bean("syntheticReadiness")
    fun syntheticReadiness(): HealthIndicator = HealthIndicator { Health.down().build() }
}
