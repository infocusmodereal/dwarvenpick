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
    val uploadsEnabled: Boolean = false,
    val maven: MavenDriverDownloadProperties = MavenDriverDownloadProperties(),
)

data class MavenDriverDownloadProperties(
    val enabled: Boolean = false,
    val repositoryUrl: String = "https://repo1.maven.org/maven2/",
    val maxJarSizeMb: Int = 50,
)
