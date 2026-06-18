package com.dwarvenpick.app.auth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

data class AuthAuditEvent(
    val type: String,
    val actor: String?,
    val outcome: String,
    val ipAddress: String?,
    val details: Map<String, Any?>,
    val timestamp: Instant = Instant.now(),
)

interface AuthAuditLogger {
    fun log(event: AuthAuditEvent)
}

@Component
class AuthAuditEventStore(
    private val authAuditEventRepository: AuthAuditEventRepository,
) {
    fun append(event: AuthAuditEvent) = authAuditEventRepository.append(event)

    fun snapshot(): List<AuthAuditEvent> = authAuditEventRepository.snapshot()

    fun query(
        type: String?,
        actor: String?,
        outcome: String?,
        from: Instant?,
        to: Instant?,
        limit: Int,
    ): List<AuthAuditEvent> = authAuditEventRepository.query(type, actor, outcome, from, to, limit)

    fun pruneOlderThan(cutoff: Instant): Int = authAuditEventRepository.pruneOlderThan(cutoff)

    fun clear() = authAuditEventRepository.clear()
}

@Component
class Slf4jAuthAuditLogger(
    private val authAuditEventStore: AuthAuditEventStore,
) : AuthAuditLogger {
    private val logger = LoggerFactory.getLogger(Slf4jAuthAuditLogger::class.java)

    override fun log(event: AuthAuditEvent) {
        authAuditEventStore.append(event)
        logger.info(
            "auth_audit type={} actor={} outcome={} ip={} details={}",
            event.type,
            event.actor ?: "anonymous",
            event.outcome,
            event.ipAddress ?: "unknown",
            event.details,
        )
    }
}
