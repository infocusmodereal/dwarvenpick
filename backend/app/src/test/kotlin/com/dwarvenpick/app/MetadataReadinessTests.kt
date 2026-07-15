package com.dwarvenpick.app

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MetadataReadinessTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `metadata database failure removes readiness without failing liveness`() {
        mockMvc
            .get("/actuator/health/readiness")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("UP") }
            }

        (dataSource as HikariDataSource).close()

        mockMvc
            .get("/actuator/health/readiness")
            .andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.status") { value("DOWN") }
            }
        mockMvc
            .get("/actuator/health/liveness")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("UP") }
            }
    }
}
