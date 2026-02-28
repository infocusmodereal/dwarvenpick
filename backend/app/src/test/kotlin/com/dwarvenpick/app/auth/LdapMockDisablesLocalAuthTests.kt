package com.dwarvenpick.app.auth

import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "dwarvenpick.auth.password-policy.min-length=8",
        "dwarvenpick.auth.ldap.mock.enabled=true",
    ],
)
@AutoConfigureMockMvc
class LdapMockDisablesLocalAuthTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `local authentication is disabled when ldap is enabled`() {
        mockMvc
            .perform(get("/api/auth/methods"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.methods", hasItem("ldap")))
            .andExpect(jsonPath("$.methods", not(hasItem("local"))))

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
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("Local authentication is disabled."))
    }
}
