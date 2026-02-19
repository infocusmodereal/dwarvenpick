package com.badgermole.app.auth

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

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
class AuthAuditEventStore {
    private val events = CopyOnWriteArrayList<AuthAuditEvent>()

    fun append(event: AuthAuditEvent) {
        events.add(event)
    }

    fun snapshot(): List<AuthAuditEvent> = events.toList()

    fun clear() {
        events.clear()
    }
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
