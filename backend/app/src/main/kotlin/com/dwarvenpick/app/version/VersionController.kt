package com.dwarvenpick.app.version

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/version")
class VersionController(
    buildPropertiesProvider: ObjectProvider<BuildProperties>,
) {
    private val buildProperties: BuildProperties? = buildPropertiesProvider.ifAvailable

    @GetMapping
    fun version(): VersionResponse =
        VersionResponse(
            service = buildProperties?.name ?: "dwarvenpick-backend",
            version = buildProperties?.version ?: "unknown",
            artifact = buildProperties?.artifact ?: "unknown",
            group = buildProperties?.group ?: "unknown",
            buildTime = buildProperties?.time?.toString() ?: "unknown",
        )
}

data class VersionResponse(
    val service: String,
    val version: String,
    val artifact: String,
    val group: String,
    val buildTime: String,
)
