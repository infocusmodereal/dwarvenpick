package com.dwarvenpick.app.datasource

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "dwarvenpick.seed.enabled=false",
        "dwarvenpick.datasource.network-guard.enabled=true",
        "dwarvenpick.datasource.network-guard.allow-private-networks=true",
    ],
)
class DatasourceNetworkGuardSpringTests {
    @Autowired
    private lateinit var datasourceRegistryService: DatasourceRegistryService

    @Test
    fun `managed datasource creation blocks restricted local addresses when guard is enabled`() {
        assertThatThrownBy {
            datasourceRegistryService.createDatasource(
                CreateDatasourceRequest(
                    name = "metadata-endpoint",
                    engine = DatasourceEngine.POSTGRESQL,
                    host = "169.254.169.254",
                    port = 5432,
                ),
            )
        }.isInstanceOf(ForbiddenNetworkTargetException::class.java)
            .hasMessage("Datasource host resolves to a restricted local address blocked by network guard policy.")
    }
}
