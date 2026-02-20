package com.badgermole.app.datasource

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "badgermole.datasource.network-guard")
data class DatasourceNetworkGuardProperties(
    val enabled: Boolean = true,
    val allowPrivateNetworks: Boolean = true,
    val allowHostPatterns: List<String> = emptyList(),
    val denyHostPatterns: List<String> = emptyList(),
    val allowCidrs: List<String> = emptyList(),
    val denyCidrs: List<String> = emptyList(),
)
