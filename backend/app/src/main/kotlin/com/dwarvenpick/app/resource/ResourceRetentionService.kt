package com.dwarvenpick.app.resource

import com.dwarvenpick.app.monitoring.MaintenanceMetrics
import com.dwarvenpick.app.monitoring.MaintenanceOutcome
import com.dwarvenpick.app.monitoring.RetentionAction
import com.dwarvenpick.app.monitoring.RetentionScope
import com.dwarvenpick.app.monitoring.RetentionStore
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class ResourceRetentionService(
    private val resourceRepository: ResourceRepository,
    private val resourceProperties: ResourceProperties,
    private val maintenanceMetrics: MaintenanceMetrics,
) {
    private val logger = LoggerFactory.getLogger(ResourceRetentionService::class.java)

    @Scheduled(fixedDelayString = "\${dwarvenpick.resources.retention-cleanup-interval-ms:3600000}")
    fun pruneResourceVersions() {
        try {
            val removedVersions = pruneResourceVersions(Instant.now())
            maintenanceMetrics.recordRows(
                RetentionStore.RESOURCE_VERSIONS,
                RetentionAction.PRUNED,
                removedVersions,
            )
            maintenanceMetrics.recordCleanup(RetentionScope.RESOURCE, MaintenanceOutcome.SUCCESS, Instant.now())
            if (removedVersions > 0) {
                logger.info("Resource version retention cleanup removed {} old version(s).", removedVersions)
            }
        } catch (exception: RuntimeException) {
            maintenanceMetrics.recordCleanup(RetentionScope.RESOURCE, MaintenanceOutcome.FAILURE, Instant.now())
            logger.error(
                "Resource version retention cleanup failed; the next scheduled run will retry. exceptionType={}",
                exception.javaClass.simpleName,
            )
        }
    }

    private fun pruneResourceVersions(now: Instant): Int {
        val cutoff =
            now.minus(
                Duration.ofDays(resourceProperties.versionRetentionDays.coerceAtLeast(1)),
            )
        return resourceRepository.pruneVersions(
            cutoff = cutoff,
            maxVersionsPerResource = resourceProperties.maxVersionsPerResource,
        )
    }
}
