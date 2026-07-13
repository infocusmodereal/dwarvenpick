package com.dwarvenpick.app.version

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/version")
class VersionController(
    private val versionMetadataProvider: VersionMetadataProvider,
) {
    @GetMapping
    fun version(): VersionResponse = versionMetadataProvider.current()
}
