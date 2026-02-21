package com.badgermole.app.auth

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class AuditEventResponse(
    val type: String,
    val actor: String?,
    val outcome: String,
    val ipAddress: String?,
    val details: Map<String, Any?>,
    val timestamp: String,
)

@RestController
@RequestMapping("/api/admin/audit-events")
class AuditAdminController(
    private val authAuditEventStore: AuthAuditEventStore,
) {
    @GetMapping
    fun listAuditEvents(
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) actor: String?,
        @RequestParam(required = false) outcome: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) limit: Int?,
    ): ResponseEntity<Any> =
        try {
            val fromInstant = parseInstantParam("from", from)
            val toInstant = parseInstantParam("to", to)
            if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
                throw IllegalArgumentException("from must be less than or equal to to.")
            }

            val events =
                authAuditEventStore
                    .query(
                        type = type,
                        actor = actor,
                        outcome = outcome,
                        from = fromInstant,
                        to = toInstant,
                        limit = limit ?: 200,
                    ).map { event ->
                        AuditEventResponse(
                            type = event.type,
                            actor = event.actor,
                            outcome = event.outcome,
                            ipAddress = event.ipAddress,
                            details = event.details,
                            timestamp = event.timestamp.toString(),
                        )
                    }

            ResponseEntity.ok(events)
        } catch (ex: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(ex.message ?: "Bad request."))
        }

    private fun parseInstantParam(
        name: String,
        value: String?,
    ): Instant? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Instant.parse(trimmed) }
            .getOrElse {
                throw IllegalArgumentException("$name must be an ISO-8601 timestamp.")
            }
    }
}
