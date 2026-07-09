package com.dwarvenpick.app.datasource

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class DatasourceNetworkGuardPropertiesTests {
    @Test
    fun `binds network guard policy lists from comma separated values`() {
        val properties =
            bind(
                mapOf(
                    "dwarvenpick.datasource.network-guard.enabled" to "true",
                    "dwarvenpick.datasource.network-guard.allow-private-networks" to "true",
                    "dwarvenpick.datasource.network-guard.deny-host-patterns" to
                        "localhost,*.localhost,metadata.google.internal",
                    "dwarvenpick.datasource.network-guard.deny-cidrs" to
                        "127.0.0.0/8,169.254.0.0/16,::1/128,fe80::/10",
                    "dwarvenpick.datasource.network-guard.allow-host-patterns" to
                        "*.indexexchange.com,*.indexww.com",
                ),
            )

        assertThat(properties.enabled).isTrue()
        assertThat(properties.allowPrivateNetworks).isTrue()
        assertThat(properties.denyHostPatterns)
            .containsExactly("localhost", "*.localhost", "metadata.google.internal")
        assertThat(properties.denyCidrs)
            .containsExactly("127.0.0.0/8", "169.254.0.0/16", "::1/128", "fe80::/10")
        assertThat(properties.allowHostPatterns).containsExactly("*.indexexchange.com", "*.indexww.com")
    }

    @Test
    fun `empty network guard list values bind to empty lists`() {
        val properties =
            bind(
                mapOf(
                    "dwarvenpick.datasource.network-guard.deny-cidrs" to "",
                    "dwarvenpick.datasource.network-guard.deny-host-patterns" to "",
                ),
            )

        assertThat(properties.denyCidrs).isEmpty()
        assertThat(properties.denyHostPatterns).isEmpty()
    }

    private fun bind(values: Map<String, String>): DatasourceNetworkGuardProperties =
        Binder(MapConfigurationPropertySource(values))
            .bind(
                "dwarvenpick.datasource.network-guard",
                Bindable.of(DatasourceNetworkGuardProperties::class.java),
            ).get()
}
