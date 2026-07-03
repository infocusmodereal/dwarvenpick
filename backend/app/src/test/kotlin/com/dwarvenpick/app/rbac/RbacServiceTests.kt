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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "dwarvenpick.auth.password-policy.min-length=8",
    ],
)
class RbacServiceTests {
    @Autowired
    private lateinit var datasourceRegistryService: DatasourceRegistryService

    @Autowired
    private lateinit var rbacService: RbacService

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
                    maxRowsPerQuery = 5000,
                    maxRuntimeSeconds = 300,
                    concurrencyLimit = 5,
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
