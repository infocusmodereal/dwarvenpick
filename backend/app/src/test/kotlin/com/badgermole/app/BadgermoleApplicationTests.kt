package com.badgermole.app

import com.badgermole.app.auth.AuthAuditEventStore
import com.badgermole.app.auth.UserAccountService
import com.badgermole.app.datasource.DatasourceRegistryService
import com.badgermole.app.rbac.RbacService
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsStringIgnoringCase
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpSession
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import com.jayway.jsonpath.JsonPath

@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "badgermole.auth.password-policy.min-length=8",
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

    @Autowired
    private lateinit var datasourceRegistryService: DatasourceRegistryService

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
        val sessionCookie = loginLocalUser("admin", "Admin1234!")

        mockMvc
            .perform(get("/api/auth/me").session(sessionCookie))
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
        val adminSession = loginLocalUser("admin", "Admin1234!")

        mockMvc
            .perform(
                post("/api/auth/admin/users/analyst/reset-password")
                    .with(csrf())
                    .session(adminSession)
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
            .perform(get("/api/auth/me").session(updatedSession))
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
                .toSession()

        mockMvc
            .perform(get("/api/auth/me").session(ldapSession))
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
                          "password": "Admin1234!"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error", containsString("CSRF")))
    }

    @Test
    fun `logout invalidates user session`() {
        val adminSession = loginLocalUser("admin", "Admin1234!")

        mockMvc
            .perform(
                post("/api/auth/logout")
                    .with(csrf())
                    .session(adminSession),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("logged_out"))

        mockMvc
            .perform(get("/api/auth/me").session(adminSession))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `admin endpoints require authentication and system admin role`() {
        mockMvc
            .perform(get("/api/admin/groups"))
            .andExpect(status().isUnauthorized)

        val analystSession = loginLocalUser("analyst", "Analyst123!")
        mockMvc
            .perform(get("/api/admin/groups").session(analystSession))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `system admin can manage groups and members`() {
        val adminSession = loginLocalUser("admin", "Admin1234!")

        mockMvc
            .perform(
                post("/api/admin/groups")
                    .with(csrf())
                    .session(adminSession)
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
                    .session(adminSession)
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
                    .session(adminSession),
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
        val adminSession = loginLocalUser("admin", "Admin1234!")

        mockMvc
            .perform(
                put("/api/admin/datasource-access/analytics-users/postgresql-core")
                    .with(csrf())
                    .session(adminSession)
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
            .andExpect(jsonPath("$.datasourceId").value("postgresql-core"))
            .andExpect(jsonPath("$.canQuery").value(true))
    }

    @Test
    fun `users only see permitted datasources and forbidden queries return 403`() {
        val analystSession = loginLocalUser("analyst", "Analyst123!")

        mockMvc
            .perform(get("/api/datasources").session(analystSession))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value("trino-warehouse"))

        mockMvc
            .perform(
                post("/api/queries")
                    .with(csrf())
                    .session(analystSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "datasourceId": "postgresql-core",
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
                    .session(analystSession)
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
            .andExpect(jsonPath("$.queryHash").isString)
    }

    @Test
    fun `query status and paginated results endpoints work for successful execution`() {
        val analystSession = loginLocalUser("analyst", "Analyst123!")
        val submitResult =
            mockMvc
                .perform(
                    post("/api/queries")
                        .with(csrf())
                        .session(analystSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "datasourceId": "trino-warehouse",
                              "sql": "select generate_series(1,25)"
                            }
                            """.trimIndent(),
                        ),
                )
                .andExpect(status().isOk)
                .andReturn()
        val executionId = jsonPathValue(submitResult, "$.executionId")

        val finalStatus = waitForExecutionTerminalStatus(analystSession, executionId)
        assertThat(finalStatus).isEqualTo("SUCCEEDED")

        val firstPage =
            mockMvc
                .perform(
                    get("/api/queries/$executionId/results")
                        .session(analystSession)
                        .queryParam("pageSize", "10"),
                )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.rows.length()").value(10))
                .andExpect(jsonPath("$.nextPageToken").isString)
                .andReturn()
        val nextPageToken = jsonPathValue(firstPage, "$.nextPageToken")

        mockMvc
            .perform(
                get("/api/queries/$executionId/results")
                    .session(analystSession)
                    .queryParam("pageSize", "10")
                    .queryParam("pageToken", nextPageToken),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rows.length()").value(10))
            .andExpect(jsonPath("$.rows[0][0]").value("11"))
    }

    @Test
    fun `cancel endpoint transitions execution to canceled and results are unavailable`() {
        val analystSession = loginLocalUser("analyst", "Analyst123!")
        val submitResult =
            mockMvc
                .perform(
                    post("/api/queries")
                        .with(csrf())
                        .session(analystSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "datasourceId": "trino-warehouse",
                              "sql": "select pg_sleep(5)"
                            }
                            """.trimIndent(),
                        ),
                )
                .andExpect(status().isOk)
                .andReturn()
        val executionId = jsonPathValue(submitResult, "$.executionId")

        mockMvc
            .perform(
                post("/api/queries/$executionId/cancel")
                    .with(csrf())
                    .session(analystSession),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELED"))

        val finalStatus = waitForExecutionTerminalStatus(analystSession, executionId)
        assertThat(finalStatus).isEqualTo("CANCELED")

        mockMvc
            .perform(get("/api/queries/$executionId/results").session(analystSession))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error", containsStringIgnoringCase("canceled")))
    }

    @Test
    fun `query concurrency limits are enforced per user`() {
        val analystSession = loginLocalUser("analyst", "Analyst123!")

        val firstExecutionId =
            jsonPathValue(
                mockMvc
                    .perform(
                        post("/api/queries")
                            .with(csrf())
                            .session(analystSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                """
                                {
                                  "datasourceId": "trino-warehouse",
                                  "sql": "select pg_sleep(3)"
                                }
                                """.trimIndent(),
                            ),
                    )
                    .andExpect(status().isOk)
                    .andReturn(),
                "$.executionId",
            )
        val secondExecutionId =
            jsonPathValue(
                mockMvc
                    .perform(
                        post("/api/queries")
                            .with(csrf())
                            .session(analystSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                """
                                {
                                  "datasourceId": "trino-warehouse",
                                  "sql": "select pg_sleep(3)"
                                }
                                """.trimIndent(),
                            ),
                    )
                    .andExpect(status().isOk)
                    .andReturn(),
                "$.executionId",
            )

        mockMvc
            .perform(
                post("/api/queries")
                    .with(csrf())
                    .session(analystSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "datasourceId": "trino-warehouse",
                          "sql": "select pg_sleep(3)"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.error", containsString("Concurrent query limit reached")))

        mockMvc
            .perform(post("/api/queries/$firstExecutionId/cancel").with(csrf()).session(analystSession))
            .andExpect(status().isOk)
        mockMvc
            .perform(post("/api/queries/$secondExecutionId/cancel").with(csrf()).session(analystSession))
            .andExpect(status().isOk)
    }

    @Test
    fun `query status events endpoint is available to authenticated users`() {
        val analystSession = loginLocalUser("analyst", "Analyst123!")

        mockMvc
            .perform(get("/api/queries/events").session(analystSession))
            .andExpect(status().isOk)
    }

    @Test
    fun `system admin can manage datasource catalog and encrypted credential profiles`() {
        val adminSession = loginLocalUser("admin", "Admin1234!")

        mockMvc
            .perform(
                post("/api/admin/datasource-management")
                    .with(csrf())
                    .session(adminSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "MySQL Sandbox",
                          "engine": "MYSQL",
                          "host": "localhost",
                          "port": 3306,
                          "database": "sandbox",
                          "driverId": "mysql-default",
                          "pool": {
                            "maximumPoolSize": 2,
                            "minimumIdle": 1,
                            "connectionTimeoutMs": 1000,
                            "idleTimeoutMs": 2000
                          },
                          "tls": {
                            "mode": "DISABLE",
                            "verifyServerCertificate": false,
                            "allowSelfSigned": true
                          }
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value("mysql-sandbox"))

        mockMvc
            .perform(
                put("/api/admin/datasource-management/mysql-sandbox/credentials/admin-ro")
                    .with(csrf())
                    .session(adminSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "username": "sandbox_reader",
                          "password": "SuperSecret123!",
                          "description": "Sandbox read profile"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profileId").value("admin-ro"))
            .andExpect(jsonPath("$.encryptionKeyId").value("v1"))

        val encrypted = datasourceRegistryService.encryptedPasswordForProfile("mysql-sandbox", "admin-ro")
        assertThat(encrypted).isNotBlank()
        assertThat(encrypted).doesNotContain("SuperSecret123!")
    }

    @Test
    fun `connection test endpoint enforces role and returns sanitized failure`() {
        val analystSession = loginLocalUser("analyst", "Analyst123!")
        mockMvc
            .perform(
                post("/api/datasources/postgresql-core/test-connection")
                    .with(csrf())
                    .session(analystSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "credentialProfile": "admin-ro",
                          "validationQuery": "SELECT 1"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isForbidden)

        val adminSession = loginLocalUser("admin", "Admin1234!")
        mockMvc
            .perform(
                post("/api/datasources/postgresql-core/test-connection")
                    .with(csrf())
                    .session(adminSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "credentialProfile": "admin-ro",
                          "validationQuery": "SELECT 1"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error", containsString("Failed to initialize pool")))
    }

    @Test
    fun `vertica connection test returns actionable error when external driver is missing`() {
        val adminSession = loginLocalUser("admin", "Admin1234!")

        mockMvc
            .perform(
                post("/api/admin/datasource-management")
                    .with(csrf())
                    .session(adminSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Vertica Lake",
                          "engine": "VERTICA",
                          "host": "vertica.local",
                          "port": 5433,
                          "database": "lake",
                          "driverId": "vertica-external"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error", containsString("Driver")))
    }

    @Test
    fun `system admin can list drivers and reencrypt credential profiles`() {
        val adminSession = loginLocalUser("admin", "Admin1234!")

        mockMvc
            .perform(get("/api/admin/drivers").session(adminSession))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].driverId").isString())
            .andExpect(jsonPath("$[0].engine").isString())
            .andExpect(jsonPath("$[0].message").isString())

        mockMvc
            .perform(
                post("/api/admin/datasource-management/credentials/reencrypt")
                    .with(csrf())
                    .session(adminSession),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.updatedProfiles").isNumber)
            .andExpect(jsonPath("$.activeKeyId").value("v1"))
    }

    @Test
    fun `system admin can update and delete managed datasource entries`() {
        val adminSession = loginLocalUser("admin", "Admin1234!")

        mockMvc
            .perform(
                post("/api/admin/datasource-management")
                    .with(csrf())
                    .session(adminSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "MariaDB Sales",
                          "engine": "MARIADB",
                          "host": "localhost",
                          "port": 3306,
                          "database": "sales",
                          "driverId": "mariadb-default"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value("mariadb-sales"))

        mockMvc
            .perform(
                patch("/api/admin/datasource-management/mariadb-sales")
                    .with(csrf())
                    .session(adminSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "host": "mariadb.internal",
                          "port": 3307,
                          "database": "sales_analytics"
                        }
                        """.trimIndent(),
                    ),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.host").value("mariadb.internal"))
            .andExpect(jsonPath("$.port").value(3307))
            .andExpect(jsonPath("$.database").value("sales_analytics"))

        mockMvc
            .perform(
                delete("/api/admin/datasource-management/mariadb-sales")
                    .with(csrf())
                    .session(adminSession),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deleted").value(true))
    }

    private fun loginLocalUser(username: String, password: String): MockHttpSession =
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
            .andReturn()
            .toSession()

    private fun waitForExecutionTerminalStatus(
        sessionCookie: MockHttpSession,
        executionId: String,
        timeoutMs: Long = 6000,
    ): String {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val result =
                mockMvc
                    .perform(get("/api/queries/$executionId").session(sessionCookie))
                    .andExpect(status().isOk)
                    .andReturn()
            val status = jsonPathValue(result, "$.status")
            if (status == "SUCCEEDED" || status == "FAILED" || status == "CANCELED") {
                return status
            }

            Thread.sleep(100)
        }

        throw AssertionError("Timed out waiting for terminal query status for execution $executionId")
    }

    private fun jsonPathValue(result: MvcResult, path: String): String =
        JsonPath.parse(result.response.contentAsString).read(path)

    private fun MvcResult.toSession(): MockHttpSession =
        (request.getSession(false) as? MockHttpSession)
            ?: throw AssertionError("Expected authenticated session.")
}
