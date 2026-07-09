package com.dwarvenpick.app.datasource

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DatasourceNetworkGuardTests {
    @Test
    fun `deny cidr blocks metadata address even when private networks are allowed`() {
        val guard =
            networkGuard(
                allowPrivateNetworks = true,
                denyCidrs = listOf("169.254.0.0/16"),
            )

        assertThatThrownBy { guard.validateHost("169.254.169.254") }
            .isInstanceOf(ForbiddenNetworkTargetException::class.java)
            .hasMessage("Datasource host resolves to an address blocked by network guard policy.")
    }

    @Test
    fun `deny cidr blocks ipv4 mapped metadata address`() {
        val guard =
            networkGuard(
                allowPrivateNetworks = true,
                denyCidrs = listOf("169.254.0.0/16"),
            )

        assertThatThrownBy { guard.validateHost("::ffff:169.254.169.254") }
            .isInstanceOf(ForbiddenNetworkTargetException::class.java)
            .hasMessage("Datasource host resolves to an address blocked by network guard policy.")
    }

    @Test
    fun `deny cidr blocks loopback address`() {
        val guard =
            networkGuard(
                allowPrivateNetworks = true,
                denyCidrs = listOf("127.0.0.0/8", "::1/128"),
            )

        assertThatThrownBy { guard.validateHost("127.0.0.1") }
            .isInstanceOf(ForbiddenNetworkTargetException::class.java)
            .hasMessage("Datasource host resolves to an address blocked by network guard policy.")
    }

    @Test
    fun `restricted local addresses are blocked even without explicit deny cidrs`() {
        val guard = networkGuard(allowPrivateNetworks = true)

        assertThatThrownBy { guard.validateHost("127.0.0.1") }
            .isInstanceOf(ForbiddenNetworkTargetException::class.java)
            .hasMessage("Datasource host resolves to a restricted local address blocked by network guard policy.")
        assertThatThrownBy { guard.validateHost("169.254.169.254") }
            .isInstanceOf(ForbiddenNetworkTargetException::class.java)
            .hasMessage("Datasource host resolves to a restricted local address blocked by network guard policy.")
    }

    @Test
    fun `unresolved host fails by default when address policy requires resolution`() {
        val guard =
            networkGuard(
                allowPrivateNetworks = true,
                denyCidrs = listOf("169.254.0.0/16"),
            )

        assertThatThrownBy { guard.validateHost("unresolved.datasource.invalid") }
            .isInstanceOf(UnresolvedNetworkTargetException::class.java)
            .hasMessage("Datasource host could not be resolved for network guard validation.")
    }

    @Test
    fun `unresolved host can be deferred by explicit bootstrap validation mode`() {
        val guard =
            networkGuard(
                allowPrivateNetworks = true,
                denyCidrs = listOf("169.254.0.0/16"),
            )

        assertThatCode {
            guard.validateHost("unresolved.datasource.invalid", allowUnresolvedHost = true)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `deferred unresolved validation still enforces host pattern and literal address blocks`() {
        val guard =
            networkGuard(
                allowPrivateNetworks = true,
                denyHostPatterns = listOf("metadata.*"),
            )

        assertThatThrownBy { guard.validateHost("metadata.google.internal", allowUnresolvedHost = true) }
            .isInstanceOf(ForbiddenNetworkTargetException::class.java)
            .hasMessage("Datasource host is blocked by network guard policy.")
        assertThatThrownBy { guard.validateHost("169.254.169.254", allowUnresolvedHost = true) }
            .isInstanceOf(ForbiddenNetworkTargetException::class.java)
            .hasMessage("Datasource host resolves to a restricted local address blocked by network guard policy.")
    }

    @Test
    fun `approved private and public literal addresses pass when outside denied ranges`() {
        val guard =
            networkGuard(
                allowPrivateNetworks = true,
                denyCidrs = listOf("127.0.0.0/8", "169.254.0.0/16"),
            )

        assertThatCode { guard.validateHost("10.10.20.30") }.doesNotThrowAnyException()
        assertThatCode { guard.validateHost("8.8.8.8") }.doesNotThrowAnyException()
    }

    @Test
    fun `deny host pattern blocks before dns resolution`() {
        val guard =
            networkGuard(
                denyHostPatterns = listOf("localhost", "*.localhost", "metadata.google.internal"),
            )

        assertThatThrownBy { guard.validateHost("metadata.google.internal") }
            .isInstanceOf(ForbiddenNetworkTargetException::class.java)
            .hasMessage("Datasource host is blocked by network guard policy.")
    }

    @Test
    fun `allow host pattern permits matching unresolved hosts when cidr validation is not configured`() {
        val guard =
            networkGuard(
                allowHostPatterns = listOf("*.indexexchange.com", "*.indexww.com"),
            )

        assertThatCode { guard.validateHost("warehouse.indexexchange.com") }.doesNotThrowAnyException()
    }

    @Test
    fun `host wildcard patterns support leading trailing middle and case insensitive matches`() {
        val guard =
            networkGuard(
                denyHostPatterns =
                    listOf(
                        "*.localhost",
                        "metadata.*",
                        "db-*-prod.indexww.com",
                        "MIXED.example.com",
                    ),
            )

        listOf(
            "api.localhost",
            "metadata.google.internal",
            "db-viper2-prod.indexww.com",
            "mixed.example.com",
        ).forEach { host ->
            assertThatThrownBy { guard.validateHost(host) }
                .isInstanceOf(ForbiddenNetworkTargetException::class.java)
                .hasMessage("Datasource host is blocked by network guard policy.")
        }
    }

    @Test
    fun `deny host pattern takes precedence over allow host pattern`() {
        val guard =
            networkGuard(
                allowHostPatterns = listOf("*.google.internal"),
                denyHostPatterns = listOf("metadata.*"),
            )

        assertThatThrownBy { guard.validateHost("metadata.google.internal") }
            .isInstanceOf(ForbiddenNetworkTargetException::class.java)
            .hasMessage("Datasource host is blocked by network guard policy.")
    }

    private fun networkGuard(
        allowPrivateNetworks: Boolean = true,
        allowHostPatterns: List<String> = emptyList(),
        denyHostPatterns: List<String> = emptyList(),
        allowCidrs: List<String> = emptyList(),
        denyCidrs: List<String> = emptyList(),
    ): DatasourceNetworkGuard =
        DatasourceNetworkGuard(
            DatasourceNetworkGuardProperties(
                enabled = true,
                allowPrivateNetworks = allowPrivateNetworks,
                allowHostPatterns = allowHostPatterns,
                denyHostPatterns = denyHostPatterns,
                allowCidrs = allowCidrs,
                denyCidrs = denyCidrs,
            ),
        )
}
