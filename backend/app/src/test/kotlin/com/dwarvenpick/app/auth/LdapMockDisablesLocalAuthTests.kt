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
        "dwarvenpick.auth.ldap.mock.users[0].username=legacy.admin",
        "dwarvenpick.auth.ldap.mock.users[0].password=Directory123!",
        "dwarvenpick.auth.ldap.mock.users[0].groups[0]=directory-admins",
        "dwarvenpick.auth.ldap.system-admin-groups[0]=directory-admins",
    ],
)
@AutoConfigureMockMvc
class LdapMockDisablesLocalAuthTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var accounts: UserAccountRepository

    @Autowired private lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    @Test
    fun `verified offline linking restores an external administrator without local login`() {
        accounts.save(
            UserAccount(
                username = "legacy.admin",
                displayName = "Legacy admin",
                email = null,
                passwordHash = null,
                provider = AuthProvider.LDAP,
                enabled = true,
                temporaryPassword = false,
                roles = mutableSetOf("SYSTEM_ADMIN", "USER"),
                groups = mutableSetOf(),
                userId = "legacy:legacy.admin",
            ),
        )

        fun login() =
            mockMvc.perform(
                post("/api/auth/ldap/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"username":"legacy.admin","password":"Directory123!"}"""),
            )
        login().andExpect(status().isConflict)
        val linked =
            jdbc.update(
                """
                INSERT INTO app_external_identities (provider, issuer, subject, user_id)
                SELECT provider, 'ldap-directory', 'uid=legacy.admin,ou=people,dc=example,dc=org', user_id
                FROM app_users
                WHERE username = 'legacy.admin' AND provider = 'LDAP' AND enabled = TRUE
                  AND user_id LIKE 'legacy:%'
                  AND NOT EXISTS (
                    SELECT 1 FROM app_external_identities links WHERE links.user_id = app_users.user_id
                  )
                """.trimIndent(),
            )
        org.assertj.core.api.Assertions
            .assertThat(linked)
            .isEqualTo(1)
        val result = login().andExpect(status().isOk).andReturn()
        val session = requireNotNull(result.response.getCookie("SESSION"))
        mockMvc
            .perform(get("/api/auth/me").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.roles", hasItem("SYSTEM_ADMIN")))
        org.assertj.core.api.Assertions
            .assertThat(accounts.find("admin"))
            .isNull()
    }

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
