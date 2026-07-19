package com.dwarvenpick.app.rbac

import com.dwarvenpick.app.auth.AuthProvider
import com.dwarvenpick.app.auth.AuthenticatedUserPrincipal
import com.dwarvenpick.app.auth.UserAccountService
import com.dwarvenpick.app.datasource.CreateDatasourceRequest
import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.datasource.DatasourceRegistryService
import com.dwarvenpick.app.datasource.TlsMode
import com.dwarvenpick.app.datasource.TlsSettings
import com.dwarvenpick.app.datasource.UpsertCredentialProfileRequest
import com.dwarvenpick.app.query.QueryJustificationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "dwarvenpick.auth.password-policy.min-length=8",
        "dwarvenpick.query.require-write-justification=true",
        "dwarvenpick.query.max-concurrency-per-user=3",
    ],
)
class RbacServiceTests {
    @Autowired
    private lateinit var datasourceRegistryService: DatasourceRegistryService

    @Autowired
    private lateinit var rbacService: RbacService

    @Autowired
    private lateinit var effectiveDatasourcePolicyService: EffectiveDatasourcePolicyService

    @Autowired
    private lateinit var userAccountService: UserAccountService

    @BeforeEach
    fun resetState() {
        userAccountService.resetState()
        rbacService.resetState()
    }

    @Test
    fun `default query policy resolves readOnly from the selected credential profile`() {
        val datasourceId = createDatasourceWithProfiles()
        rbacService.createGroup(CreateGroupRequest(name = "alpha-readers"))
        rbacService.createGroup(CreateGroupRequest(name = "omega-writers"))
        rbacService.upsertDatasourceAccess(
            groupId = "alpha-readers",
            datasourceId = datasourceId,
            request =
                UpsertDatasourceAccessRequest(
                    credentialProfile = "read-only",
                    canQuery = true,
                    canExport = false,
                    readOnly = true,
                    maxRowsPerQuery = 100,
                    maxRuntimeSeconds = 30,
                    concurrencyLimit = 1,
                ),
        )
        rbacService.upsertDatasourceAccess(
            groupId = "omega-writers",
            datasourceId = datasourceId,
            request =
                UpsertDatasourceAccessRequest(
                    credentialProfile = "read-write",
                    canQuery = true,
                    canExport = true,
                    readOnly = false,
                    maxRowsPerQuery = 0,
                    maxRuntimeSeconds = 300,
                    concurrencyLimit = 0,
                ),
        )

        val policy = rbacService.resolveQueryAccessPolicy(overlappingPrincipal(), datasourceId)

        assertThat(policy.credentialProfile).isEqualTo("read-only")
        assertThat(policy.readOnly).isTrue()
        assertThat(policy.maxRowsPerQuery).isEqualTo(100)
        assertThat(policy.maxRuntimeSeconds).isEqualTo(30)
        assertThat(policy.concurrencyLimit).isEqualTo(1)
    }

    @Test
    fun `requested elevated profile resolves its own readOnly policy for overlapping groups`() {
        val datasourceId = createDatasourceWithProfiles()
        rbacService.createGroup(CreateGroupRequest(name = "alpha-readers"))
        rbacService.createGroup(CreateGroupRequest(name = "omega-writers"))
        rbacService.upsertDatasourceAccess(
            groupId = "alpha-readers",
            datasourceId = datasourceId,
            request =
                UpsertDatasourceAccessRequest(
                    credentialProfile = "read-only",
                    canQuery = true,
                    canExport = false,
                    readOnly = true,
                ),
        )
        rbacService.upsertDatasourceAccess(
            groupId = "omega-writers",
            datasourceId = datasourceId,
            request =
                UpsertDatasourceAccessRequest(
                    credentialProfile = "read-write",
                    canQuery = true,
                    canExport = true,
                    readOnly = false,
                ),
        )

        val policy =
            rbacService.resolveQueryAccessPolicy(
                principal = overlappingPrincipal(),
                datasourceId = datasourceId,
                requestedCredentialProfile = "read-write",
            )

        assertThat(policy.credentialProfile).isEqualTo("read-write")
        assertThat(policy.readOnly).isFalse()
    }

    @Test
    fun `catalog policies are principal scoped ordered and match enforced profile access`() {
        val datasourceId = createDatasourceWithProfiles()
        rbacService.createGroup(CreateGroupRequest(name = "alpha-readers"))
        rbacService.createGroup(CreateGroupRequest(name = "omega-writers"))
        rbacService.upsertDatasourceAccess(
            groupId = "alpha-readers",
            datasourceId = datasourceId,
            request =
                UpsertDatasourceAccessRequest(
                    credentialProfile = "read-only",
                    canQuery = true,
                    canExport = false,
                    readOnly = true,
                    maxRowsPerQuery = 100,
                    maxRuntimeSeconds = 30,
                    concurrencyLimit = 1,
                ),
        )
        rbacService.upsertDatasourceAccess(
            groupId = "omega-writers",
            datasourceId = datasourceId,
            request =
                UpsertDatasourceAccessRequest(
                    credentialProfile = "read-write",
                    canQuery = true,
                    canExport = true,
                    readOnly = false,
                    maxRowsPerQuery = 5000,
                    maxRuntimeSeconds = 300,
                    concurrencyLimit = 5,
                ),
        )

        val overlapPolicies =
            effectiveDatasourcePolicyService
                .listPermittedDatasources(overlappingPrincipal())
                .single { datasource -> datasource.id == datasourceId }
                .credentialProfilePolicies
        assertThat(overlapPolicies.map { policy -> policy.credentialProfile })
            .containsExactly("read-only", "read-write")
        val readOnlyPolicy = overlapPolicies.first()
        assertThat(readOnlyPolicy.readOnly).isTrue()
        assertThat(readOnlyPolicy.canExport).isFalse()
        assertThat(readOnlyPolicy.maxRowsPerQuery).isEqualTo(100)
        assertThat(readOnlyPolicy.maxRuntimeSeconds).isEqualTo(30)
        assertThat(readOnlyPolicy.concurrencyLimit).isEqualTo(1)
        assertThat(readOnlyPolicy.justificationMode).isEqualTo(QueryJustificationMode.NONE)

        val writePolicy = overlapPolicies.last()
        assertThat(writePolicy.readOnly).isFalse()
        assertThat(writePolicy.canExport).isTrue()
        assertThat(writePolicy.maxRowsPerQuery).isEqualTo(5000)
        assertThat(writePolicy.concurrencyLimit).isEqualTo(3)
        assertThat(writePolicy.justificationMode).isEqualTo(QueryJustificationMode.PROFILE_REQUIRED)

        val readerOnly =
            overlappingPrincipal().copy(
                username = "reader.only",
                groups = setOf("alpha-readers"),
            )
        val readerPolicies =
            effectiveDatasourcePolicyService
                .listPermittedDatasources(readerOnly)
                .single { datasource -> datasource.id == datasourceId }
                .credentialProfilePolicies
        assertThat(readerPolicies.map { policy -> policy.credentialProfile }).containsExactly("read-only")
    }

    @Test
    fun `export authorization follows the exact executed credential profile for overlapping groups`() {
        val datasourceId = createDatasourceWithProfiles()
        rbacService.createGroup(CreateGroupRequest(name = "alpha-readers"))
        rbacService.createGroup(CreateGroupRequest(name = "omega-writers"))
        rbacService.upsertDatasourceAccess(
            groupId = "alpha-readers",
            datasourceId = datasourceId,
            request =
                UpsertDatasourceAccessRequest(
                    credentialProfile = "read-only",
                    canQuery = true,
                    canExport = false,
                    readOnly = true,
                ),
        )
        rbacService.upsertDatasourceAccess(
            groupId = "omega-writers",
            datasourceId = datasourceId,
            request =
                UpsertDatasourceAccessRequest(
                    credentialProfile = "read-write",
                    canQuery = true,
                    canExport = true,
                    readOnly = false,
                ),
        )

        val principal = overlappingPrincipal()

        assertThat(rbacService.canUserExport(principal, datasourceId, "read-only")).isFalse()
        assertThat(rbacService.canUserExport(principal, datasourceId, "read-write")).isTrue()
        assertThat(rbacService.canUserExport(principal, datasourceId, "  ")).isFalse()
    }

    @Test
    fun `system admin export bypass requires a concrete execution profile`() {
        val datasourceId = createDatasourceWithProfiles()
        val principal =
            AuthenticatedUserPrincipal(
                username = "admin",
                displayName = "Administrator",
                email = null,
                provider = AuthProvider.LOCAL,
                roles = setOf("SYSTEM_ADMIN", "USER"),
                groups = emptySet(),
            )

        assertThat(rbacService.canUserExport(principal, datasourceId, "read-write")).isTrue()
        assertThat(rbacService.canUserExport(principal, datasourceId, " ")).isFalse()
    }

    private fun createDatasourceWithProfiles(): String {
        val datasourceId =
            datasourceRegistryService
                .createDatasource(
                    CreateDatasourceRequest(
                        name = "overlap starrocks",
                        engine = DatasourceEngine.STARROCKS,
                        host = "localhost",
                        port = 9030,
                        driverId = "starrocks-mysql",
                        tls = TlsSettings(mode = TlsMode.DISABLE),
                    ),
                ).id

        datasourceRegistryService.upsertCredentialProfile(
            datasourceId,
            "read-only",
            UpsertCredentialProfileRequest(
                username = "reader",
                password = "reader-password",
            ),
        )
        datasourceRegistryService.upsertCredentialProfile(
            datasourceId,
            "read-write",
            UpsertCredentialProfileRequest(
                username = "writer",
                password = "writer-password",
            ),
        )
        return datasourceId
    }

    private fun overlappingPrincipal(): AuthenticatedUserPrincipal =
        AuthenticatedUserPrincipal(
            username = "ldap.user",
            displayName = "LDAP User",
            email = "ldap.user@example.local",
            provider = AuthProvider.LDAP,
            roles = setOf("USER"),
            groups = setOf("alpha-readers", "omega-writers"),
        )
}
