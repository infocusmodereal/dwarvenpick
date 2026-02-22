package com.dwarvenpick.app.auth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

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

    fun query(
        type: String?,
        actor: String?,
        outcome: String?,
        from: Instant?,
        to: Instant?,
        limit: Int,
    ): List<AuthAuditEvent> {
        val normalizedType = type?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val actorFilter = actor?.trim()?.takeIf { it.isNotBlank() }
        val actorRegex = actorFilter?.let { filter -> parseRegexFilterOrNull(filter) }
        val normalizedActor = actorFilter?.lowercase()
        val normalizedOutcome = outcome?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val resolvedLimit = limit.coerceIn(1, 1000)

        return snapshot()
            .asSequence()
            .filter { event ->
                normalizedType == null || event.type.lowercase().contains(normalizedType)
            }.filter { event ->
                if (actorRegex != null) {
                    event.actor?.let { actorValue ->
                        actorRegex.containsMatchIn(actorValue)
                    } ?: false
                } else {
                    normalizedActor == null ||
                        (event.actor?.lowercase()?.contains(normalizedActor) == true)
                }
            }.filter { event ->
                normalizedOutcome == null || event.outcome.lowercase() == normalizedOutcome
            }.filter { event ->
                from == null || !event.timestamp.isBefore(from)
            }.filter { event ->
                to == null || !event.timestamp.isAfter(to)
            }.sortedByDescending { event -> event.timestamp }
            .take(resolvedLimit)
            .toList()
    }

    private fun parseRegexFilterOrNull(rawFilter: String): Regex? {
        if (rawFilter.length < 2 || !rawFilter.startsWith('/')) {
            return null
        }
        val closingSlashIndex = rawFilter.lastIndexOf('/')
        if (closingSlashIndex <= 0) {
            return null
        }

        val pattern = rawFilter.substring(1, closingSlashIndex)
        if (pattern.isBlank()) {
            throw IllegalArgumentException("Actor regex filter cannot be empty.")
        }
        val flags = rawFilter.substring(closingSlashIndex + 1)
        val options = mutableSetOf<RegexOption>()
        flags.forEach { flag ->
            when (flag) {
                'i' -> options.add(RegexOption.IGNORE_CASE)
                'm' -> options.add(RegexOption.MULTILINE)
                's' -> options.add(RegexOption.DOT_MATCHES_ALL)
                else -> throw IllegalArgumentException("Unsupported regex flag '$flag' for actor filter.")
            }
        }

        return try {
            Regex(pattern, options)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("Actor regex filter is invalid: ${exception.message}")
        }
    }

    fun pruneOlderThan(cutoff: Instant): Int {
        var removed = 0
        events.removeIf { event ->
            if (event.timestamp.isBefore(cutoff)) {
                removed += 1
                true
            } else {
                false
            }
        }
        return removed
    }

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
