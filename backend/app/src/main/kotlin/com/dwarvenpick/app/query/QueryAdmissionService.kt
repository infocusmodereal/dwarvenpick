package com.dwarvenpick.app.query

import com.dwarvenpick.app.auth.AuthAuditEvent
import com.dwarvenpick.app.auth.AuthAuditLogger
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom

class QueryAdmissionUnavailableException(
    override val message: String,
) : RuntimeException(message)

@Service
class QueryAdmissionService(
    private val queryAdmissionRepository: QueryAdmissionRepository,
    private val queryExecutionProperties: QueryExecutionProperties,
    private val authAuditLogger: AuthAuditLogger,
    private val meterRegistry: MeterRegistry,
) {
    fun reserve(
        record: PersistedQueryRuntimeRecord,
        actorLimit: Int,
    ) {
        val limits =
            QueryAdmissionLimits(
                actor = actorLimit,
                datasource = queryExecutionProperties.maxConcurrencyPerDatasource.coerceAtLeast(1),
                global = queryExecutionProperties.maxConcurrencyGlobal.coerceAtLeast(1),
            )
        val attempts = queryExecutionProperties.admissionRetryAttempts.coerceAtLeast(1)
        repeat(attempts) { attempt ->
            val staleCutoff =
                Instant.now().minusSeconds(queryExecutionProperties.activeExecutionStaleSeconds.coerceAtLeast(60))
            when (val decision = queryAdmissionRepository.tryReserve(record, limits, staleCutoff)) {
                QueryAdmissionDecision.Admitted -> return
                QueryAdmissionDecision.Contended -> {
                    if (attempt < attempts - 1) {
                        sleepBeforeRetry(attempt)
                    }
                }
                is QueryAdmissionDecision.Limited -> reject(record, decision)
            }
        }
        unavailable(record)
    }

    private fun reject(
        record: PersistedQueryRuntimeRecord,
        decision: QueryAdmissionDecision.Limited,
    ): Nothing {
        val message =
            when (decision.scope) {
                QueryAdmissionScope.ACTOR ->
                    "Concurrent query limit reached (${decision.limit}). " +
                        "Cancel an active query before running another."
                QueryAdmissionScope.DATASOURCE ->
                    "Connection query limit reached (${decision.limit}). " +
                        "Try again after an active query finishes."
                QueryAdmissionScope.GLOBAL ->
                    "Dwarvenpick query capacity is currently full (${decision.limit}). Try again shortly."
            }
        recordAttempt(record, "blocked_concurrency")
        meterRegistry
            .counter(
                "dwarvenpick.query.admission.denials",
                "scope",
                decision.scope.metricValue,
                "datasourceId",
                record.datasourceId,
            ).increment()
        audit(record, outcome = "limited", scope = decision.scope, limit = decision.limit, message = message)
        throw QueryConcurrencyLimitException(message, decision.scope, decision.limit)
    }

    private fun unavailable(record: PersistedQueryRuntimeRecord): Nothing {
        val message = "Query admission control is busy. Try again shortly."
        meterRegistry
            .counter(
                "dwarvenpick.query.admission.failures",
                "reason",
                "lock_contention",
                "datasourceId",
                record.datasourceId,
            ).increment()
        audit(record, outcome = "unavailable", scope = null, limit = null, message = message)
        throw QueryAdmissionUnavailableException(message)
    }

    private fun sleepBeforeRetry(attempt: Int) {
        val baseDelay = queryExecutionProperties.admissionRetryBackoffMs.coerceAtLeast(1)
        val cappedMultiplier = (attempt + 1).coerceAtMost(10)
        val jitter = ThreadLocalRandom.current().nextLong(baseDelay)
        try {
            Thread.sleep(baseDelay * cappedMultiplier + jitter)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw QueryAdmissionUnavailableException("Query admission was interrupted. Try again.")
        }
    }

    private fun recordAttempt(
        record: PersistedQueryRuntimeRecord,
        outcome: String,
    ) {
        meterRegistry
            .counter(
                "dwarvenpick.query.execute.attempts",
                "outcome",
                outcome,
                "datasourceId",
                record.datasourceId,
            ).increment()
    }

    private fun audit(
        record: PersistedQueryRuntimeRecord,
        outcome: String,
        scope: QueryAdmissionScope?,
        limit: Int?,
        message: String,
    ) {
        authAuditLogger.log(
            AuthAuditEvent(
                type = "query.execute",
                actor = record.actor,
                outcome = outcome,
                ipAddress = record.ipAddress,
                details =
                    mapOf(
                        "datasourceId" to record.datasourceId,
                        "credentialProfile" to record.credentialProfile,
                        "admissionScope" to scope?.metricValue,
                        "admissionLimit" to limit,
                        "reason" to message,
                    ),
            ),
        )
    }
}
