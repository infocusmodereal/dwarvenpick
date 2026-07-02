package com.dwarvenpick.app

import com.dwarvenpick.app.auth.UserAccountService
import com.dwarvenpick.app.resource.CreateResourceRequest
import com.dwarvenpick.app.resource.ResourceScope
import com.dwarvenpick.app.resource.ResourceService
import com.dwarvenpick.app.resource.UpdateResourceContentRequest
import com.dwarvenpick.app.snippet.CreateSnippetRequest
import com.dwarvenpick.app.snippet.SnippetService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "dwarvenpick.auth.password-policy.min-length=8",
        "dwarvenpick.auth.local.allow-with-ldap=true",
    ],
)
class ResourceAndSnippetListingScalabilityTests {
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
    fun `resource lists return summaries while detail preserves full sql`() {
        val principal = createPrincipal("resource.list.user")
        val largeSql = "SELECT '" + "x".repeat(2_000) + "' AS payload"
        val largerSql = "SELECT '" + "y".repeat(3_000) + "' AS payload"

        val created =
            resourceService.createResource(
                principal = principal,
                request =
                    CreateResourceRequest(
                        title = "large-resource",
                        sql = largeSql,
                        scope = ResourceScope.PRIVATE,
                        tags = listOf("heavy"),
                    ),
            )
        resourceService.updateContent(
            principal = principal,
            resourceId = created.resourceId,
            request =
                UpdateResourceContentRequest(
                    title = "large-resource",
                    sql = largerSql,
                ),
        )

        val summaries =
            resourceService.listResources(
                principal = principal,
                scope = "all",
                query = "payload",
                groupId = null,
                datasourceId = null,
                tag = "heavy",
                limit = 10,
                offset = 0,
            )

        val summary = summaries.single { it.resourceId == created.resourceId }
        assertThat(summary.sqlPreview).endsWith("...")
        assertThat(summary.sqlPreview.length).isLessThan(largerSql.length)
        assertThat(summary.sqlLength).isEqualTo(largerSql.length)
        assertThat(summary.versionCount).isEqualTo(2)

        val detail = resourceService.getResource(principal, created.resourceId)
        assertThat(detail.sql).isEqualTo(largerSql)

        val wildcardSummaries =
            resourceService.listResources(
                principal = principal,
                scope = "all",
                query = "_",
                groupId = null,
                datasourceId = null,
                tag = null,
                limit = 10,
                offset = 0,
            )
        assertThat(wildcardSummaries).noneMatch { summary -> summary.resourceId == created.resourceId }
    }

    @Test
    fun `snippet lists return summaries while detail preserves full sql`() {
        val principal = createPrincipal("snippet.list.user")
        val largeSql = "SELECT '" + "z".repeat(2_000) + "' AS snippet_payload"

        val created =
            snippetService.createSnippet(
                principal = principal,
                request = CreateSnippetRequest(title = "large-snippet", sql = largeSql),
            )

        val summary =
            snippetService
                .listSnippets(
                    principal = principal,
                    scope = "personal",
                    title = "large-snippet",
                    titleMatch = null,
                    groupId = null,
                ).single { it.snippetId == created.snippetId }

        assertThat(summary.sqlPreview).endsWith("...")
        assertThat(summary.sqlPreview.length).isLessThan(largeSql.length)
        assertThat(summary.sqlLength).isEqualTo(largeSql.length)

        val detail = snippetService.getSnippet(principal, created.snippetId)
        assertThat(detail.sql).isEqualTo(largeSql)
    }

    @Test
    fun `resource summary lists enforce owner and group visibility in sql`() {
        val admin = createPrincipal("resource.admin")
        val owner = createPrincipal("resource.owner", systemAdmin = false)
        val analyst = createPrincipal("resource.analyst", systemAdmin = false).copy(groups = setOf("analytics"))
        val outsider = createPrincipal("resource.outsider", systemAdmin = false)
        val privateResource =
            resourceService.createResource(
                principal = owner,
                request =
                    CreateResourceRequest(
                        title = "private-resource",
                        sql = "SELECT 'private'",
                        scope = ResourceScope.PRIVATE,
                    ),
            )
        val sharedResource =
            resourceService.createResource(
                principal = admin,
                request =
                    CreateResourceRequest(
                        title = "analytics-resource",
                        sql = "SELECT 'analytics'",
                        scope = ResourceScope.SHARED,
                        groupId = "analytics",
                    ),
            )
        val otherSharedResource =
            resourceService.createResource(
                principal = admin,
                request =
                    CreateResourceRequest(
                        title = "finance-resource",
                        sql = "SELECT 'finance'",
                        scope = ResourceScope.SHARED,
                        groupId = "finance",
                    ),
            )

        val analystResourceIds = resourceService.listResources(analyst, "all", null, null, null, null, 10, 0).map { it.resourceId }
        assertThat(analystResourceIds)
            .contains(sharedResource.resourceId)
            .doesNotContain(privateResource.resourceId, otherSharedResource.resourceId)

        val ownerResourceIds = resourceService.listResources(owner, "all", null, null, null, null, 10, 0).map { it.resourceId }
        assertThat(ownerResourceIds)
            .contains(privateResource.resourceId)
            .doesNotContain(sharedResource.resourceId, otherSharedResource.resourceId)

        val outsiderResourceIds = resourceService.listResources(outsider, "all", null, null, null, null, 10, 0).map { it.resourceId }
        assertThat(outsiderResourceIds)
            .doesNotContain(privateResource.resourceId, sharedResource.resourceId, otherSharedResource.resourceId)
    }

    @Test
    fun `snippet summary lists enforce owner and group visibility in sql`() {
        val admin = createPrincipal("snippet.admin")
        val owner = createPrincipal("snippet.owner", systemAdmin = false)
        val analyst = createPrincipal("snippet.analyst", systemAdmin = false).copy(groups = setOf("analytics"))
        val outsider = createPrincipal("snippet.outsider", systemAdmin = false)
        val personalSnippet =
            snippetService.createSnippet(
                principal = owner,
                request = CreateSnippetRequest(title = "personal-snippet", sql = "SELECT 'personal'"),
            )
        val sharedSnippet =
            snippetService.createSnippet(
                principal = admin,
                request =
                    CreateSnippetRequest(
                        title = "analytics-snippet",
                        sql = "SELECT 'analytics'",
                        groupId = "analytics",
                    ),
            )
        val otherSharedSnippet =
            snippetService.createSnippet(
                principal = admin,
                request =
                    CreateSnippetRequest(
                        title = "finance-snippet",
                        sql = "SELECT 'finance'",
                        groupId = "finance",
                    ),
            )

        val analystSnippetIds = snippetService.listSnippets(analyst, "all", null, null, null).map { it.snippetId }
        assertThat(analystSnippetIds)
            .contains(sharedSnippet.snippetId)
            .doesNotContain(personalSnippet.snippetId, otherSharedSnippet.snippetId)

        val ownerSnippetIds = snippetService.listSnippets(owner, "all", null, null, null).map { it.snippetId }
        assertThat(ownerSnippetIds)
            .contains(personalSnippet.snippetId)
            .doesNotContain(sharedSnippet.snippetId, otherSharedSnippet.snippetId)

        val outsiderSnippetIds = snippetService.listSnippets(outsider, "all", null, null, null).map { it.snippetId }
        assertThat(outsiderSnippetIds)
            .doesNotContain(personalSnippet.snippetId, sharedSnippet.snippetId, otherSharedSnippet.snippetId)
    }

    @Test
    fun `snippet regex filters fail instead of silently truncating scan window`() {
        val principal = createPrincipal("snippet.regex.user", systemAdmin = false)
        repeat(1_001) { index ->
            snippetService.createSnippet(
                principal = principal,
                request = CreateSnippetRequest(title = "regex-scan-$index", sql = "SELECT $index"),
            )
        }

        assertThatThrownBy {
            snippetService.listSnippets(
                principal = principal,
                scope = "personal",
                title = "/regex-scan-.*/",
                titleMatch = "regex",
                groupId = null,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("scan at most")
    }

    private fun createPrincipal(
        username: String,
        systemAdmin: Boolean = true,
    ) = userAccountService.createLocalUser(
        username = username,
        displayName = username,
        email = "$username@example.local",
        password = "Persistent123!",
        temporaryPassword = false,
        systemAdmin = systemAdmin,
    )
}
