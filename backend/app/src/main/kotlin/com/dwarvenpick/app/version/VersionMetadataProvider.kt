package com.dwarvenpick.app.version

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class VersionMetadataProvider(
    buildPropertiesProvider: ObjectProvider<BuildProperties>,
    private val environment: Environment,
) {
    private val buildProperties: BuildProperties? = buildPropertiesProvider.getIfAvailable()

    fun current(): VersionResponse =
        VersionResponse(
            service = buildProperties?.name ?: "dwarvenpick-backend",
            version = buildProperties?.version ?: "unknown",
            artifact = buildProperties?.artifact ?: "unknown",
            group = buildProperties?.group ?: "unknown",
            buildTime = buildProperties?.time?.toString() ?: "unknown",
            sourceSha = metadataValue("DWARVENPICK_SOURCE_SHA", "source.sha"),
            sourceRef = metadataValue("DWARVENPICK_SOURCE_REF", "source.ref"),
            imageTag = metadataValue("DWARVENPICK_IMAGE_TAG", "image.tag"),
            buildTag = metadataValue("DWARVENPICK_BUILD_TAG", "build.tag"),
        )

    private fun metadataValue(
        envName: String,
        buildPropertyName: String,
    ): String =
        environment.getProperty(envName)?.trim()?.takeIf { it.isNotBlank() }
            ?: buildProperties?.get(buildPropertyName)?.trim()?.takeIf { it.isNotBlank() }
            ?: "unknown"
}

data class VersionResponse(
    val service: String,
    val version: String,
    val artifact: String,
    val group: String,
    val buildTime: String,
    val sourceSha: String,
    val sourceRef: String,
    val imageTag: String,
    val buildTag: String,
)
