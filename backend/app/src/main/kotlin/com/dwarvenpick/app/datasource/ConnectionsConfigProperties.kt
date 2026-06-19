package com.dwarvenpick.app.datasource

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dwarvenpick.connections")
data class ConnectionsConfigProperties(
    /**
     * Optional filesystem path to a YAML file that bootstraps connections, credential profiles, and (optionally)
     * group-to-connection access rules.
     *
     * When empty, no connections are bootstrapped.
     */
    val configPath: String = "",
    /**
     * When true, YAML config is treated as the desired state for config-managed connections: removed connections,
     * credential profiles, and access mappings are removed from persistent state during startup.
     */
    val authoritative: Boolean = true,
    /**
     * When true (default), the application will fail fast if the config file references a missing environment variable
     * via `${ENV:VAR_NAME}`.
     */
    val failOnMissingEnv: Boolean = true,
)
