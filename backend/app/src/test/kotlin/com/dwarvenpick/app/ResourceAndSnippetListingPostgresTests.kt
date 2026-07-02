package com.dwarvenpick.app

import com.dwarvenpick.app.auth.UserAccountService
import com.dwarvenpick.app.resource.CreateResourceRequest
import com.dwarvenpick.app.resource.ResourceScope
import com.dwarvenpick.app.resource.ResourceService
import com.dwarvenpick.app.snippet.CreateSnippetRequest
import com.dwarvenpick.app.snippet.SnippetService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(
    properties = [
        "dwarvenpick.auth.password-policy.min-length=8",
        "dwarvenpick.auth.local.allow-with-ldap=true",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
class ResourceAndSnippetListingPostgresTests {
    @Autowired
    private lateinit var userAccountService: UserAccountService

    @Autowired
    private lateinit var resourceService: ResourceService

    @Autowired
    private lateinit var snippetService: SnippetService

    @BeforeEach
    fun resetState() {
        resourceService.clear()
        snippetService.clear()
        userAccountService.resetState()
    }

    @Test
    fun `postgres lists resources and snippets when optional filters are null`() {
        val principal =
            userAccountService.createLocalUser(
                username = "postgres.list.user",
                displayName = "postgres.list.user",
                email = "postgres.list.user@example.local",
                password = "Persistent123!",
                temporaryPassword = false,
                systemAdmin = true,
            )

        val resource =
            resourceService.createResource(
                principal = principal,
                request =
                    CreateResourceRequest(
                        title = "postgres-resource",
                        sql = "SELECT 'resource'",
                        scope = ResourceScope.PRIVATE,
                    ),
            )
        val snippet =
            snippetService.createSnippet(
                principal = principal,
                request = CreateSnippetRequest(title = "postgres-snippet", sql = "SELECT 'snippet'"),
            )

        assertThat(
            resourceService
                .listResources(
                    principal = principal,
                    scope = "all",
                    query = null,
                    groupId = null,
                    datasourceId = null,
                    tag = null,
                    limit = 10,
                    offset = 0,
                ).map { it.resourceId },
        ).contains(resource.resourceId)

        assertThat(
            snippetService
                .listSnippets(
                    principal = principal,
                    scope = "all",
                    title = null,
                    titleMatch = null,
                    groupId = null,
                ).map { it.snippetId },
        ).contains(snippet.snippetId)
    }

    companion object {
        @Container
        @JvmStatic
        private val postgresContainer = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgresContainer::getJdbcUrl)
            registry.add("spring.datasource.username", postgresContainer::getUsername)
            registry.add("spring.datasource.password", postgresContainer::getPassword)
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
        }
    }
}
