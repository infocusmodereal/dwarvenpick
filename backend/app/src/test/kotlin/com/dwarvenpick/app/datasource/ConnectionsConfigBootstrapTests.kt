package com.dwarvenpick.app.datasource

import com.dwarvenpick.app.rbac.GroupResponse
import com.dwarvenpick.app.rbac.RbacService
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.boot.DefaultApplicationArguments
import java.nio.file.Files

class ConnectionsConfigBootstrapTests {
    @Test
    fun `bootstrap defers unresolved host validation for config managed datasources`() {
        val yaml =
            """
            connections:
              - name: starrocks-adhoc-dev
                engine: STARROCKS
                host: unresolved.datasource.invalid
                port: 9030
                credentialProfiles:
                  read-only:
                    username: reader
                    password: secret
            """.trimIndent()
        val temp = Files.createTempFile("dwarvenpick-connections", ".yaml")
        Files.writeString(temp, yaml)

        val datasourceRegistryService = mock(DatasourceRegistryService::class.java)
        val rbacService = mock(RbacService::class.java)
        doReturn(emptyList<GroupResponse>()).`when`(rbacService).listGroups()
        doReturn(
            ManagedDatasourceResponse(
                id = "starrocks-adhoc-dev",
                name = "starrocks-adhoc-dev",
                engine = DatasourceEngine.STARROCKS.name,
                host = "unresolved.datasource.invalid",
                port = 9030,
                database = null,
                driverId = "starrocks-default",
                driverClass = "com.mysql.cj.jdbc.Driver",
                pool = PoolSettings(),
                tls = TlsSettings(),
                options = emptyMap(),
                credentialProfiles = emptyList(),
            ),
        ).`when`(datasourceRegistryService).upsertManagedDatasource(anyRequest(), eqString("config"), eqBoolean(true))

        ConnectionsConfigBootstrap(
            connectionsConfigProperties =
                ConnectionsConfigProperties(
                    configPath = temp.toString(),
                    authoritative = false,
                ),
            datasourceRegistryService = datasourceRegistryService,
            rbacService = rbacService,
        ).run(DefaultApplicationArguments())

        verify(datasourceRegistryService).upsertManagedDatasource(
            anyRequest(),
            eqString("config"),
            eqBoolean(true),
        )
    }

    private fun anyRequest(): CreateDatasourceRequest {
        ArgumentMatchers.any(CreateDatasourceRequest::class.java)
        return CreateDatasourceRequest()
    }

    private fun eqString(value: String): String {
        ArgumentMatchers.eq(value)
        return value
    }

    private fun eqBoolean(value: Boolean): Boolean {
        ArgumentMatchers.eq(value)
        return value
    }
}
