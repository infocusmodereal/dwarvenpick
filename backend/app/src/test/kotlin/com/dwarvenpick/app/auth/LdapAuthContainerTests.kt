package com.dwarvenpick.app.auth

import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.time.Duration

@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "dwarvenpick.auth.password-policy.min-length=8",
        "dwarvenpick.auth.ldap.enabled=true",
        "dwarvenpick.auth.ldap.user-search-base=ou=people,dc=example,dc=org",
        "dwarvenpick.auth.ldap.group-sync.enabled=true",
        "dwarvenpick.auth.ldap.group-sync.group-search-base=ou=groups,dc=example,dc=org",
        "dwarvenpick.auth.ldap.group-sync.mapping-rules.ldap-analysts=ANALYSTS",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class LdapAuthContainerTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userAccountService: UserAccountService

    @BeforeEach
    fun resetState() {
        userAccountService.resetState()
    }

    @Test
    fun `auth methods include ldap when enabled`() {
        mockMvc
            .perform(get("/api/auth/methods"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.methods", hasItem("ldap")))
    }

    @Test
    fun `ldap login succeeds and provisions user`() {
        val session = loginLdapUser(username = "ldap.user", password = "LdapUser123!")

        mockMvc
            .perform(get("/api/auth/me").session(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("ldap.user"))
            .andExpect(jsonPath("$.provider").value("ldap"))
            .andExpect(jsonPath("$.groups", hasItem("ANALYSTS")))
    }

    @Test
    fun `ldap login fails with invalid credentials`() {
        mockMvc
            .perform(
                post("/api/auth/ldap/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "username": "ldap.user",
                          "password": "wrong-password"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error", containsString("LDAP authentication failed")))
    }

    private fun loginLdapUser(
        username: String,
        password: String,
    ): MockHttpSession =
        mockMvc
            .perform(
                post("/api/auth/ldap/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "username": "$username",
                          "password": "$password"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isOk)
            .andReturn()
            .toSession()

    private fun MvcResult.toSession(): MockHttpSession =
        (request.getSession(false) as? MockHttpSession)
            ?: throw AssertionError("Expected authenticated session.")

    companion object {
        private const val LDAP_PORT = 389

        @JvmStatic
        @Container
        val ldapContainer: GenericContainer<Nothing> =
            GenericContainer<Nothing>("osixia/openldap:1.5.0").apply {
                withEnv("LDAP_ORGANISATION", "Example Org")
                withEnv("LDAP_DOMAIN", "example.org")
                withEnv("LDAP_ADMIN_PASSWORD", "admin")
                withEnv("LDAP_TLS", "false")
                withCopyFileToContainer(
                    MountableFile.forClasspathResource("ldap/bootstrap.ldif"),
                    "/container/service/slapd/assets/config/bootstrap/ldif/50-bootstrap.ldif",
                )
                withExposedPorts(LDAP_PORT)
                waitingFor(
                    Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)),
                )
            }

        @JvmStatic
        @DynamicPropertySource
        fun registerLdapProperties(registry: DynamicPropertyRegistry) {
            registry.add("dwarvenpick.auth.ldap.url") {
                "ldap://${ldapContainer.host}:${ldapContainer.getMappedPort(LDAP_PORT)}"
            }
            registry.add("dwarvenpick.auth.ldap.bind-dn") { "cn=admin,dc=example,dc=org" }
            registry.add("dwarvenpick.auth.ldap.bind-password") { "admin" }
        }
    }
}
