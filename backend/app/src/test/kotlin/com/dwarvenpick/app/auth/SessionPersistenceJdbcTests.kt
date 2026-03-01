package com.dwarvenpick.app.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "spring.session.store-type=jdbc",
        "dwarvenpick.auth.password-policy.min-length=8",
    ],
)
@AutoConfigureMockMvc
class SessionPersistenceJdbcTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `login persists session record when jdbc session store is enabled`() {
        mockMvc
            .perform(
                post("/api/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "username": "admin",
                          "password": "Admin1234!"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isOk)

        val sessionCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION",
                Long::class.java,
            ) ?: 0L
        assertThat(sessionCount).isGreaterThan(0L)
    }
}
