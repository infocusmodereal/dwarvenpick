package com.dwarvenpick.app

import com.dwarvenpick.app.auth.AuthAuditEvent
import com.dwarvenpick.app.auth.AuthAuditEventStore
import com.dwarvenpick.app.auth.AuthProvider
import com.dwarvenpick.app.auth.UserAccountService
import com.dwarvenpick.app.controlplane.DatasourcePauseService
import com.dwarvenpick.app.datasource.CreateDatasourceRequest
import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.datasource.DatasourceRegistryService
import com.dwarvenpick.app.datasource.TlsMode
import com.dwarvenpick.app.datasource.TlsSettings
import com.dwarvenpick.app.datasource.UploadedDriverRegistration
import com.dwarvenpick.app.datasource.UploadedDriverRepository
import com.dwarvenpick.app.datasource.UpsertCredentialProfileRequest
import com.dwarvenpick.app.rbac.CreateGroupRequest
import com.dwarvenpick.app.rbac.RbacService
import com.dwarvenpick.app.rbac.UpsertDatasourceAccessRequest
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
import java.nio.file.Path

@SpringBootTest(
    properties = [
        "dwarvenpick.auth.password-policy.min-length=8",
        "dwarvenpick.auth.local.allow-with-ldap=true",
    ],
)
class PersistentRuntimeStateTests {
    @Autowired
    private lateinit var authAuditEventStore: AuthAuditEventStore

    @Autowired
    private lateinit var userAccountService: UserAccountService

    @Autowired
    private lateinit var rbacService: RbacService

    @Autowired
    private lateinit var datasourceRegistryService: DatasourceRegistryService

    @Autowired
    private lateinit var datasourcePauseService: DatasourcePauseService

    @Autowired
    private lateinit var snippetService: SnippetService

    @Autowired
    private lateinit var resourceService: ResourceService

    @Autowired
    private lateinit var uploadedDriverRepository: UploadedDriverRepository

    @BeforeEach
    fun resetState() {
        snippetService.clear()
        resourceService.clear()
        authAuditEventStore.clear()
        datasourcePauseService.clear()
        uploadedDriverRepository.clear()
        userAccountService.resetState()
        rbacService.resetState()
    }

    @Test
    fun `runtime state persists through jdbc stores`() {
        val principal =
            userAccountService.createLocalUser(
                username = "persist.user",
                displayName = "Persistent User",
                email = "persist.user@example.local",
                password = "Persistent123!",
                temporaryPassword = false,
                systemAdmin = true,
            )

        authAuditEventStore.append(
            AuthAuditEvent(
                type = "test.persistence",
                actor = principal.username,
                outcome = "succeeded",
                ipAddress = "127.0.0.1",
                details = mapOf("scope" to "runtime-state"),
            ),
        )
        assertThat(authAuditEventStore.snapshot()).anyMatch { event -> event.type == "test.persistence" }

        val snippet =
            snippetService.createSnippet(
                principal = principal,
                request = CreateSnippetRequest(title = "persist-snippet", sql = "SELECT 1"),
            )
        assertThat(snippetService.listSnippets(principal, null, null, null, null))
            .extracting<String> { it.snippetId }
            .contains(snippet.snippetId)

        val resource =
            resourceService.createResource(
                principal = principal,
                request =
                    CreateResourceRequest(
                        title = "persist-resource",
                        sql = "SELECT 2",
                        scope = ResourceScope.PRIVATE,
                    ),
            )
        assertThat(resourceService.listVersions(principal, resource.resourceId)).hasSize(1)

        val datasource =
            datasourceRegistryService.createDatasource(
                CreateDatasourceRequest(
                    name = "persistent-postgres",
                    engine = DatasourceEngine.POSTGRESQL,
                    host = "localhost",
                    port = 5432,
                    database = "postgres",
                    driverId = "postgres-default",
                    tls = TlsSettings(mode = TlsMode.DISABLE),
                ),
            )
        datasourceRegistryService.upsertCredentialProfile(
            datasourceId = datasource.id,
            profileId = "persist-ro",
            request =
                UpsertCredentialProfileRequest(
                    username = "persist_ro",
                    password = "secret",
                    description = "Persistent readonly profile",
                ),
        )
        assertThat(datasourceRegistryService.listCredentialProfiles(datasource.id))
            .extracting<String> { it.profileId }
            .contains("persist-ro")

        val group = rbacService.createGroup(CreateGroupRequest(name = "persist-group"))
        rbacService.addMember(group.id, principal.username)
        rbacService.upsertDatasourceAccess(
            groupId = group.id,
            datasourceId = datasource.id,
            request =
                UpsertDatasourceAccessRequest(
                    canQuery = true,
                    canExport = true,
                    readOnly = true,
                    credentialProfile = "persist-ro",
                ),
        )
        assertThat(rbacService.canUserQuery(principal.copy(groups = setOf(group.id)), datasource.id)).isTrue()

        datasourcePauseService.pause(datasource.id, pausedBy = principal.username, reason = "test")
        assertThat(datasourcePauseService.isPaused(datasource.id)).isTrue()

        uploadedDriverRepository.save(
            UploadedDriverRegistration(
                driverId = "persist-driver",
                engine = DatasourceEngine.POSTGRESQL,
                driverClass = "org.postgresql.Driver",
                description = "Persistent test driver",
                jarPath = Path.of("/tmp/persist-driver.jar"),
            ),
        )
        assertThat(uploadedDriverRepository.list())
            .extracting<String> { it.driverId }
            .contains("persist-driver")

        assertThat(userAccountService.currentUserPrincipal(principal.username)?.provider).isEqualTo(AuthProvider.LOCAL)
    }
}
