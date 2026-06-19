package com.dwarvenpick.app.resource

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dwarvenpick.resources")
data class ResourceProperties(
    val versionRetentionDays: Long = 180,
    val maxVersionsPerResource: Int = 100,
    val retentionCleanupIntervalMs: Long = 3_600_000,
)
