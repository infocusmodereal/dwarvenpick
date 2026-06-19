package com.dwarvenpick.app.query

import com.dwarvenpick.app.auth.AuthAuditEventStore
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
) {
    private val logger = LoggerFactory.getLogger(QueryRetentionService::class.java)

    @Scheduled(fixedDelayString = "\${dwarvenpick.query.retention-cleanup-interval-ms:3600000}")
    fun pruneHistoryAndAudit() {
        val now = Instant.now()
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
        val removedRuntime = queryExecutionManager.pruneRuntimeOlderThan(historyCutoff)
        val removedAuditEvents = authAuditEventStore.pruneOlderThan(auditCutoff)

        val redactedHistoryEntries =
            if (queryExecutionProperties.queryTextRedactionDays > 0) {
                val redactionCutoff =
                    now.minus(
                        Duration.ofDays(
                            queryExecutionProperties.queryTextRedactionDays.coerceAtLeast(1),
                        ),
                    )
                queryExecutionManager.redactHistoryQueryTextOlderThan(redactionCutoff)
            } else {
                0
            }
        val redactedRuntimeEntries =
            if (queryExecutionProperties.queryTextRedactionDays > 0) {
                val redactionCutoff =
                    now.minus(
                        Duration.ofDays(
                            queryExecutionProperties.queryTextRedactionDays.coerceAtLeast(1),
                        ),
                    )
                queryExecutionManager.redactRuntimeQueryTextOlderThan(redactionCutoff)
            } else {
                0
            }

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
