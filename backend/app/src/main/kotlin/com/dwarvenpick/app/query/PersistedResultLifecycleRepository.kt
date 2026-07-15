package com.dwarvenpick.app.query

import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

data class PersistedResultStorageSnapshot(
    val bytes: Long,
    val pageCount: Long,
    val expiryCandidateCount: Long,
    val oldestExpiryCandidateAt: Instant?,
    val activeExportLeaseCount: Long,
)

data class PersistedResultCleanupBatch(
    val expiredExecutionIds: List<String>,
)

private data class PersistedResultSessionState(
    val status: QueryExecutionStatus,
    val lastAccessedAt: Instant,
    val resultsExpired: Boolean,
)

@Repository
class PersistedResultLifecycleRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {
    @Transactional
    fun touchResultSession(
        executionId: String,
        ttlCutoff: Instant,
        accessedAt: Instant,
    ): Boolean {
        val state = findSessionForUpdate(executionId) ?: return false
        if (!state.isReadable()) {
            return false
        }
        if (state.lastAccessedAt.isBefore(ttlCutoff) && !hasActiveExportLease(executionId, accessedAt)) {
            expireLocked(executionId, RESULT_EXPIRED_MESSAGE)
            return false
        }
        return updateLastAccessed(executionId, accessedAt) == 1
    }

    @Transactional
    fun acquireExportLease(
        executionId: String,
        leaseId: String,
        ttlCutoff: Instant,
        acquiredAt: Instant,
        expiresAt: Instant,
    ): Boolean {
        val state = findSessionForUpdate(executionId) ?: return false
        if (!state.isReadable()) {
            return false
        }
        if (state.lastAccessedAt.isBefore(ttlCutoff) && !hasActiveExportLease(executionId, acquiredAt)) {
            expireLocked(executionId, RESULT_EXPIRED_MESSAGE)
            return false
        }
        updateLastAccessed(executionId, acquiredAt)
        jdbcTemplate.update(
            """
            INSERT INTO query_runtime_result_export_leases (execution_id, lease_id, created_at, expires_at)
            VALUES (:executionId, :leaseId, :createdAt, :expiresAt)
            """.trimIndent(),
            mapOf(
                "executionId" to executionId,
                "leaseId" to leaseId,
                "createdAt" to acquiredAt.toTimestamp(),
                "expiresAt" to expiresAt.toTimestamp(),
            ),
        )
        return true
    }

    @Transactional
    fun renewExportLease(
        executionId: String,
        leaseId: String,
        accessedAt: Instant,
        expiresAt: Instant,
    ): Boolean {
        val state = findSessionForUpdate(executionId) ?: return false
        if (!state.isReadable()) {
            return false
        }
        val renewed =
            jdbcTemplate.update(
                """
                UPDATE query_runtime_result_export_leases
                SET expires_at = :expiresAt
                WHERE execution_id = :executionId
                  AND lease_id = :leaseId
                  AND expires_at > :accessedAt
                """.trimIndent(),
                mapOf(
                    "executionId" to executionId,
                    "leaseId" to leaseId,
                    "accessedAt" to accessedAt.toTimestamp(),
                    "expiresAt" to expiresAt.toTimestamp(),
                ),
            ) == 1
        if (renewed) {
            updateLastAccessed(executionId, accessedAt)
        }
        return renewed
    }

    fun releaseExportLease(
        executionId: String,
        leaseId: String,
    ): Boolean =
        jdbcTemplate.update(
            """
            DELETE FROM query_runtime_result_export_leases
            WHERE execution_id = :executionId
              AND lease_id = :leaseId
            """.trimIndent(),
            mapOf("executionId" to executionId, "leaseId" to leaseId),
        ) == 1

    fun deleteExpiredExportLeases(now: Instant): Int =
        jdbcTemplate.update(
            "DELETE FROM query_runtime_result_export_leases WHERE expires_at <= :now",
            mapOf("now" to now.toTimestamp()),
        )

    @Transactional
    fun expireIdleResults(
        ttlCutoff: Instant,
        now: Instant,
        batchSize: Int,
    ): PersistedResultCleanupBatch {
        val executionIds =
            jdbcTemplate.queryForList(
                """
                SELECT execution_id
                FROM query_runtime_executions execution
                WHERE execution.status = 'SUCCEEDED'
                  AND execution.results_expired = FALSE
                  AND execution.last_accessed_at < :ttlCutoff
                  AND NOT EXISTS (
                    SELECT 1
                    FROM query_runtime_result_export_leases lease
                    WHERE lease.execution_id = execution.execution_id
                      AND lease.expires_at > :now
                  )
                ORDER BY execution.last_accessed_at ASC, execution.execution_id ASC
                LIMIT :batchSize
                FOR UPDATE
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("ttlCutoff", ttlCutoff.toTimestamp())
                    .addValue("now", now.toTimestamp())
                    .addValue("batchSize", batchSize.coerceAtLeast(1)),
                String::class.java,
            )
        val expiredExecutionIds =
            executionIds.filter { executionId ->
                expireLocked(executionId, RESULT_EXPIRED_MESSAGE) == 1
            }
        return PersistedResultCleanupBatch(expiredExecutionIds)
    }

    fun storageSnapshot(
        ttlCutoff: Instant,
        now: Instant,
    ): PersistedResultStorageSnapshot {
        val pageStats =
            jdbcTemplate.queryForMap(
                """
                SELECT COALESCE(SUM(byte_count), 0) AS persisted_bytes,
                       COUNT(*) AS page_count
                FROM query_runtime_result_pages
                """.trimIndent(),
                emptyMap<String, Any>(),
            )
        val candidateStats =
            jdbcTemplate.queryForMap(
                """
                SELECT COUNT(*) AS candidate_count,
                       MIN(execution.last_accessed_at) AS oldest_candidate_at
                FROM query_runtime_executions execution
                WHERE execution.status = 'SUCCEEDED'
                  AND execution.results_expired = FALSE
                  AND execution.last_accessed_at < :ttlCutoff
                  AND NOT EXISTS (
                    SELECT 1
                    FROM query_runtime_result_export_leases lease
                    WHERE lease.execution_id = execution.execution_id
                      AND lease.expires_at > :now
                  )
                """.trimIndent(),
                mapOf("ttlCutoff" to ttlCutoff.toTimestamp(), "now" to now.toTimestamp()),
            )
        val activeLeaseCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM query_runtime_result_export_leases WHERE expires_at > :now",
                mapOf("now" to now.toTimestamp()),
                Long::class.java,
            ) ?: 0L
        return PersistedResultStorageSnapshot(
            bytes = (pageStats.getValue("persisted_bytes") as Number).toLong(),
            pageCount = (pageStats.getValue("page_count") as Number).toLong(),
            expiryCandidateCount = (candidateStats.getValue("candidate_count") as Number).toLong(),
            oldestExpiryCandidateAt = (candidateStats["oldest_candidate_at"] as? Timestamp)?.toInstant(),
            activeExportLeaseCount = activeLeaseCount,
        )
    }

    private fun findSessionForUpdate(executionId: String): PersistedResultSessionState? =
        try {
            jdbcTemplate.queryForObject(
                """
                SELECT status, last_accessed_at, results_expired
                FROM query_runtime_executions
                WHERE execution_id = :executionId
                FOR UPDATE
                """.trimIndent(),
                mapOf("executionId" to executionId),
            ) { resultSet, _ ->
                PersistedResultSessionState(
                    status = QueryExecutionStatus.valueOf(resultSet.getString("status")),
                    lastAccessedAt = resultSet.getTimestamp("last_accessed_at").toInstant(),
                    resultsExpired = resultSet.getBoolean("results_expired"),
                )
            }
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    private fun PersistedResultSessionState.isReadable(): Boolean = status == QueryExecutionStatus.SUCCEEDED && !resultsExpired

    private fun updateLastAccessed(
        executionId: String,
        accessedAt: Instant,
    ): Int =
        jdbcTemplate.update(
            """
            UPDATE query_runtime_executions
            SET last_accessed_at = :accessedAt
            WHERE execution_id = :executionId
            """.trimIndent(),
            mapOf("executionId" to executionId, "accessedAt" to accessedAt.toTimestamp()),
        )

    private fun hasActiveExportLease(
        executionId: String,
        observedAt: Instant,
    ): Boolean =
        (
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM query_runtime_result_export_leases
                WHERE execution_id = :executionId
                  AND expires_at > :observedAt
                """.trimIndent(),
                mapOf("executionId" to executionId, "observedAt" to observedAt.toTimestamp()),
                Long::class.java,
            ) ?: 0L
        ) > 0

    private fun expireLocked(
        executionId: String,
        message: String,
    ): Int {
        val updated =
            jdbcTemplate.update(
                """
                UPDATE query_runtime_executions
                SET rows_json = '[]',
                    columns_json = '[]',
                    row_count = 0,
                    column_count = 0,
                    results_expired = TRUE,
                    message = :message
                WHERE execution_id = :executionId
                  AND results_expired = FALSE
                """.trimIndent(),
                mapOf("executionId" to executionId, "message" to message),
            )
        if (updated == 0) {
            return 0
        }
        jdbcTemplate.update(
            "DELETE FROM query_runtime_result_pages WHERE execution_id = :executionId",
            mapOf("executionId" to executionId),
        )
        jdbcTemplate.update(
            "DELETE FROM query_runtime_result_export_leases WHERE execution_id = :executionId",
            mapOf("executionId" to executionId),
        )
        jdbcTemplate.update(
            """
            UPDATE query_history
            SET row_count = 0,
                column_count = 0,
                message = :message
            WHERE execution_id = :executionId
            """.trimIndent(),
            mapOf("executionId" to executionId, "message" to message),
        )
        return updated
    }

    private fun Instant.toTimestamp(): Timestamp = Timestamp.from(this)

    private companion object {
        private const val RESULT_EXPIRED_MESSAGE = "Result session expired. Re-run the query."
    }
}
