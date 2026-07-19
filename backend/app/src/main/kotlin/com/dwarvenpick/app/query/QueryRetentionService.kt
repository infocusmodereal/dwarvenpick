package com.dwarvenpick.app.query

import com.dwarvenpick.app.auth.AuthAuditEventStore
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
class QueryRetentionService(
    private val queryExecutionManager: QueryExecutionManager,
    private val authAuditEventStore: AuthAuditEventStore,
    private val queryExecutionProperties: QueryExecutionProperties,
    private val maintenanceMetrics: MaintenanceMetrics,
) {
    private val logger = LoggerFactory.getLogger(QueryRetentionService::class.java)

    @Scheduled(fixedDelayString = "\${dwarvenpick.query.retention-cleanup-interval-ms:3600000}")
    fun pruneHistoryAndAudit() {
        val now = Instant.now()
        try {
            pruneHistoryAndAudit(now)
            maintenanceMetrics.recordCleanup(RetentionScope.QUERY, MaintenanceOutcome.SUCCESS, Instant.now())
        } catch (exception: RuntimeException) {
            maintenanceMetrics.recordCleanup(RetentionScope.QUERY, MaintenanceOutcome.FAILURE, Instant.now())
            logger.error(
                "Query retention cleanup failed; the next scheduled run will retry. exceptionType={}",
                exception.javaClass.simpleName,
            )
        }
    }

    private fun pruneHistoryAndAudit(now: Instant) {
        val historyCutoff =
            now.minus(
                Duration.ofDays(
                    queryExecutionProperties.historyRetentionDays.coerceAtLeast(1),
                ),
            )
        val auditCutoff =
            now.minus(
                Duration.ofDays(
                    queryExecutionProperties.auditRetentionDays.coerceAtLeast(1),
                ),
            )

        val removedHistory = queryExecutionManager.pruneHistoryOlderThan(historyCutoff)
        maintenanceMetrics.recordRows(RetentionStore.QUERY_HISTORY, RetentionAction.PRUNED, removedHistory)
        val removedRuntime = queryExecutionManager.pruneRuntimeOlderThan(historyCutoff)
        maintenanceMetrics.recordRows(RetentionStore.QUERY_RUNTIME, RetentionAction.PRUNED, removedRuntime)
        val removedAuditEvents = authAuditEventStore.pruneOlderThan(auditCutoff)
        maintenanceMetrics.recordRows(RetentionStore.AUDIT_EVENTS, RetentionAction.PRUNED, removedAuditEvents)

        val redactionCutoff =
            if (queryExecutionProperties.queryTextRedactionDays > 0) {
                now.minus(
                    Duration.ofDays(
                        queryExecutionProperties.queryTextRedactionDays.coerceAtLeast(1),
                    ),
                )
            } else {
                null
            }
        val redactedHistoryEntries = redactionCutoff?.let(queryExecutionManager::redactHistoryQueryTextOlderThan) ?: 0
        maintenanceMetrics.recordRows(
            RetentionStore.QUERY_HISTORY,
            RetentionAction.REDACTED,
            redactedHistoryEntries,
        )
        val redactedRuntimeEntries =
            redactionCutoff?.let(queryExecutionManager::redactRuntimeQueryTextOlderThan) ?: 0
        maintenanceMetrics.recordRows(
            RetentionStore.QUERY_RUNTIME,
            RetentionAction.REDACTED,
            redactedRuntimeEntries,
        )

        if (
            removedHistory > 0 ||
            removedRuntime > 0 ||
            removedAuditEvents > 0 ||
            redactedHistoryEntries > 0 ||
            redactedRuntimeEntries > 0
        ) {
            logger.info(
                "Retention cleanup completed: removedHistory={}, removedRuntime={}, removedAuditEvents={}, " +
                    "redactedHistoryEntries={}, redactedRuntimeEntries={}",
                removedHistory,
                removedRuntime,
                removedAuditEvents,
                redactedHistoryEntries,
                redactedRuntimeEntries,
            )
        }
    }
}
