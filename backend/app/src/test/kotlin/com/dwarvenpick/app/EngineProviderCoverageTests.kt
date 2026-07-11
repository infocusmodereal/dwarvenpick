package com.dwarvenpick.app

import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.inspector.ObjectInspectorProvider
import com.dwarvenpick.app.systemhealth.SystemHealthProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "dwarvenpick.auth.password-policy.min-length=8",
    ],
)
class EngineProviderCoverageTests {
    @Autowired
    private lateinit var systemHealthProviders: List<SystemHealthProvider>

    @Autowired
    private lateinit var objectInspectorProviders: List<ObjectInspectorProvider>

    @Test
    fun `every advertised engine has exactly one system health provider`() {
        assertExactlyOneProviderPerEngine(
            providerType = "system health",
            claims = systemHealthProviders.map { provider -> provider.javaClass.simpleName to provider.engines },
        )
    }

    @Test
    fun `every advertised engine has exactly one object inspector provider`() {
        assertExactlyOneProviderPerEngine(
            providerType = "object inspector",
            claims = objectInspectorProviders.map { provider -> provider.javaClass.simpleName to provider.engines },
        )
    }

    private fun assertExactlyOneProviderPerEngine(
        providerType: String,
        claims: List<Pair<String, Set<DatasourceEngine>>>,
    ) {
        claims.forEach { (provider, engines) ->
            assertThat(engines)
                .describedAs("$provider must claim at least one $providerType engine")
                .isNotEmpty()
        }
        DatasourceEngine.entries.forEach { engine ->
            val matchingProviders = claims.filter { (_, engines) -> engine in engines }.map { (provider, _) -> provider }
            assertThat(matchingProviders)
                .describedAs("$providerType providers for advertised engine $engine")
                .hasSize(1)
        }
    }
}
