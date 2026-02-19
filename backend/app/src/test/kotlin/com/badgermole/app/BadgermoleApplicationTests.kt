package com.badgermole.app

import com.badgermole.app.auth.AuthAuditEventStore
import com.badgermole.app.auth.UserAccountService
import com.badgermole.app.rbac.RbacService
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "badgermole.auth.ldap.mock.enabled=true",
        "badgermole.auth.ldap.mock.users[0].username=ldap.user",
        "badgermole.auth.ldap.mock.users[0].password=LdapUser123!",
        "badgermole.auth.ldap.mock.users[0].display-name=LDAP User",
        "badgermole.auth.ldap.mock.users[0].email=ldap.user@example.local",
        "badgermole.auth.ldap.mock.users[0].groups[0]=ldap-analysts",
        "badgermole.auth.ldap.group-sync.mapping-rules.ldap-analysts=ANALYSTS",
    ],
)
@AutoConfigureMockMvc
class BadgermoleApplicationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var authAuditEventStore: AuthAuditEventStore

    @Autowired
    private lateinit var userAccountService: UserAccountService

    @Autowired
    private lateinit var rbacService: RbacService

    @BeforeEach
    fun resetState() {
        userAccountService.resetState()
        rbacService.resetState()
        authAuditEventStore.clear()
    }

    @Test
    fun `api health endpoint returns up`() {
        mockMvc
            .perform(get("/api/health"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
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

    @Test
    fun `local login succeeds and me endpoint returns profile`() {
        val sessionCookie = loginLocalUser("admin", "Admin123!")

        mockMvc
            .perform(get("/api/auth/me").cookie(sessionCookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("admin"))
            .andExpect(jsonPath("$.provider").value("local"))
            .andExpect(jsonPath("$.roles", hasItem("SYSTEM_ADMIN")))
    }

    @Test
    fun `local login failure returns friendly error and logs audit event`() {
        mockMvc
            .perform(
                post("/api/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "username": "admin",
                          "password": "wrong-password"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("Invalid username or password."))

        val events = authAuditEventStore.snapshot()
        assertThat(events)
            .anyMatch { event ->
                event.type == "auth.local.login" &&
                    event.actor == "admin" &&
                    event.outcome == "failed"
            }
    }

    @Test
    fun `disabled user is blocked from login`() {
        mockMvc
            .perform(
                post("/api/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "username": "disabled.user",
                          "password": "Disabled123!"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("User account is disabled."))
    }

    @Test
    fun `admin can reset password and new password works`() {
        val adminSession = loginLocalUser("admin", "Admin123!")

        mockMvc
            .perform(
                post("/api/auth/admin/users/analyst/reset-password")
                    .with(csrf())
                    .cookie(adminSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "newPassword": "Updated123!"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("analyst"))
            .andExpect(jsonPath("$.message").value("Password reset completed."))

        mockMvc
            .perform(
                post("/api/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "username": "analyst",
                          "password": "Analyst123!"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isUnauthorized)

        val updatedSession = loginLocalUser("analyst", "Updated123!")
        mockMvc
            .perform(get("/api/auth/me").cookie(updatedSession))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("analyst"))
    }

    @Test
    fun `ldap login auto provisions user and syncs mapped groups`() {
        val ldapSession =
            mockMvc
                .perform(
                    post("/api/auth/ldap/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "username": "ldap.user",
                              "password": "LdapUser123!"
                            }
                            """.trimIndent(),
                        ),
                )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.provider").value("ldap"))
                .andReturn()
                .response
                .getCookie("JSESSIONID")
                ?: error("Expected JSESSIONID cookie for LDAP login")

        mockMvc
            .perform(get("/api/auth/me").cookie(ldapSession))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("ldap.user"))
            .andExpect(jsonPath("$.provider").value("ldap"))
            .andExpect(jsonPath("$.groups", hasItem("ANALYSTS")))

        val events = authAuditEventStore.snapshot()
        assertThat(events)
            .anyMatch { event ->
                event.type == "auth.ldap.group_sync" &&
                    event.actor == "ldap.user" &&
                    event.outcome == "success"
            }
    }

    @Test
    fun `csrf is required for state changing requests`() {
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "username": "admin",
                          "password": "Admin123!"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error", containsString("CSRF")))
    }

    @Test
    fun `logout invalidates user session`() {
        val adminSession = loginLocalUser("admin", "Admin123!")

        mockMvc
            .perform(
                post("/api/auth/logout")
                    .with(csrf())
                    .cookie(adminSession),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("logged_out"))

        mockMvc
            .perform(get("/api/auth/me").cookie(adminSession))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `admin endpoints require authentication and system admin role`() {
        mockMvc
            .perform(get("/api/admin/groups"))
            .andExpect(status().isUnauthorized)

        val analystSession = loginLocalUser("analyst", "Analyst123!")
        mockMvc
            .perform(get("/api/admin/groups").cookie(analystSession))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `system admin can manage groups and members`() {
        val adminSession = loginLocalUser("admin", "Admin123!")

        mockMvc
            .perform(
                post("/api/admin/groups")
                    .with(csrf())
                    .cookie(adminSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Incident Responders",
                          "description": "On-call incident responders."
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value("incident-responders"))

        mockMvc
            .perform(
                post("/api/admin/groups/incident-responders/members")
                    .with(csrf())
                    .cookie(adminSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "username": "analyst"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.members", hasItem("analyst")))

        mockMvc
            .perform(
                delete("/api/admin/groups/incident-responders/members/analyst")
                    .with(csrf())
                    .cookie(adminSession),
            )
            .andExpect(status().isOk)

        val events = authAuditEventStore.snapshot()
        assertThat(events)
            .anyMatch { event ->
                event.type == "rbac.group.create" &&
                    event.actor == "admin" &&
                    event.outcome == "success"
            }
    }

    @Test
    fun `admin can manage datasource access mappings`() {
        val adminSession = loginLocalUser("admin", "Admin123!")

        mockMvc
            .perform(
                put("/api/admin/datasource-access/analytics-users/postgres-core")
                    .with(csrf())
                    .cookie(adminSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "canQuery": true,
                          "canExport": false,
                          "maxRowsPerQuery": 1000,
                          "maxRuntimeSeconds": 90,
                          "concurrencyLimit": 1,
                          "credentialProfile": "analyst-ro"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.groupId").value("analytics-users"))
            .andExpect(jsonPath("$.datasourceId").value("postgres-core"))
            .andExpect(jsonPath("$.canQuery").value(true))
    }

    @Test
    fun `users only see permitted datasources and forbidden queries return 403`() {
        val analystSession = loginLocalUser("analyst", "Analyst123!")

        mockMvc
            .perform(get("/api/datasources").cookie(analystSession))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value("trino-warehouse"))

        mockMvc
            .perform(
                post("/api/queries")
                    .with(csrf())
                    .cookie(analystSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "datasourceId": "postgres-core",
                          "sql": "select 1"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("Datasource access denied for query execution."))

        val events = authAuditEventStore.snapshot()
        assertThat(events)
            .anyMatch { event ->
                event.type == "query.execute" &&
                    event.actor == "analyst" &&
                    event.outcome == "denied"
            }
    }

    @Test
    fun `allowed datasource query returns queued response`() {
        val analystSession = loginLocalUser("analyst", "Analyst123!")

        mockMvc
            .perform(
                post("/api/queries")
                    .with(csrf())
                    .cookie(analystSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "datasourceId": "trino-warehouse",
                          "sql": "select 1"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.datasourceId").value("trino-warehouse"))
    }

    private fun loginLocalUser(username: String, password: String): Cookie =
        mockMvc
            .perform(
                post("/api/auth/login")
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
            )
            .andExpect(status().isOk)
            .andExpect(cookie().exists("JSESSIONID"))
            .andExpect(cookie().httpOnly("JSESSIONID", true))
            .andReturn()
            .response
            .getCookie("JSESSIONID")
            ?: error("Expected JSESSIONID cookie.")
}
