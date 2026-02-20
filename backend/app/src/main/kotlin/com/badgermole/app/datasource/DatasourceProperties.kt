package com.badgermole.app.datasource

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "badgermole.security.credentials")
data class CredentialEncryptionProperties(
    val activeKeyId: String = "v1",
    val masterKey: String = "badgermole-dev-master-key-change-me",
)

@ConfigurationProperties(prefix = "badgermole.drivers")
data class DriverRegistryProperties(
    val externalDir: String = "/opt/app/drivers",
)
