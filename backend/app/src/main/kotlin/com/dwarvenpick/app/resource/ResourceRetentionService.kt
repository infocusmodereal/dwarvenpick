package com.dwarvenpick.app.resource

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class ResourceRetentionService(
    private val resourceRepository: ResourceRepository,
    private val resourceProperties: ResourceProperties,
) {
    private val logger = LoggerFactory.getLogger(ResourceRetentionService::class.java)

    @Scheduled(fixedDelayString = "\${dwarvenpick.resources.retention-cleanup-interval-ms:3600000}")
    fun pruneResourceVersions() {
        val cutoff =
            Instant.now().minus(
                Duration.ofDays(resourceProperties.versionRetentionDays.coerceAtLeast(1)),
            )
        val removedVersions =
            resourceRepository.pruneVersions(
                cutoff = cutoff,
                maxVersionsPerResource = resourceProperties.maxVersionsPerResource,
            )
        if (removedVersions > 0) {
            logger.info("Resource version retention cleanup removed {} old version(s).", removedVersions)
        }
    }
}
