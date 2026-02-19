package com.badgermole.app

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
class BadgermoleApplicationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `api health endpoint returns up`() {
        mockMvc
            .perform(get("/api/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }

    @Test
    fun `actuator health endpoint returns up`() {
        mockMvc
            .perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }

    @Test
    fun `version endpoint returns build metadata`() {
        mockMvc
            .perform(get("/api/version"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.service").isString())
            .andExpect(jsonPath("$.version").isString())
            .andExpect(jsonPath("$.artifact").isString())
            .andExpect(jsonPath("$.group").isString())
            .andExpect(jsonPath("$.buildTime").isString())
    }
}
