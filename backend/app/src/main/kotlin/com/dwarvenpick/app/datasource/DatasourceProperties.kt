package com.dwarvenpick.app.datasource

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dwarvenpick.security.credentials")
data class CredentialEncryptionProperties(
    val activeKeyId: String = "v1",
    val masterKey: String = "dwarvenpick-dev-master-key-change-me",
)

@ConfigurationProperties(prefix = "dwarvenpick.drivers")
data class DriverRegistryProperties(
    val externalDir: String = "/opt/app/drivers",
)
