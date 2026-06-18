package com.dwarvenpick.app.auth

import com.dwarvenpick.app.persistence.PersistenceSchemaInitializer
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class AuthAuditEventRepository(
    jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    @Suppress("unused") private val persistenceSchemaInitializer: PersistenceSchemaInitializer,
) {
    private val namedParameterJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
    private val rowMapper = RowMapper { resultSet, _ -> resultSet.toAuthAuditEvent() }
    private val detailsType = object : TypeReference<Map<String, Any?>>() {}

    fun append(event: AuthAuditEvent) {
        namedParameterJdbcTemplate.update(
            """
            INSERT INTO auth_audit_events (
              event_id,
              event_type,
              actor,
              outcome,
              ip_address,
              details_json,
              event_timestamp
            ) VALUES (
              :eventId,
              :eventType,
              :actor,
              :outcome,
              :ipAddress,
              :detailsJson,
              :eventTimestamp
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("eventId", UUID.randomUUID().toString())
                .addValue("eventType", event.type)
                .addValue("actor", event.actor)
                .addValue("outcome", event.outcome)
                .addValue("ipAddress", event.ipAddress)
                .addValue("detailsJson", objectMapper.writeValueAsString(event.details))
                .addValue("eventTimestamp", event.timestamp.toTimestamp()),
        )
    }

    fun snapshot(): List<AuthAuditEvent> =
        namedParameterJdbcTemplate.query(
            """
            SELECT event_type, actor, outcome, ip_address, details_json, event_timestamp
            FROM auth_audit_events
            ORDER BY event_timestamp DESC, event_id DESC
            """.trimIndent(),
            emptyMap<String, Any>(),
            rowMapper,
        )

    fun query(
        type: String?,
        actor: String?,
        outcome: String?,
        from: Instant?,
        to: Instant?,
        limit: Int,
    ): List<AuthAuditEvent> {
        val parameters = MapSqlParameterSource()
        val predicates = mutableListOf<String>()
        val normalizedType = type?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val actorFilter = actor?.trim()?.takeIf { it.isNotBlank() }
        val normalizedOutcome = outcome?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

        if (normalizedType != null) {
            predicates.add("LOWER(event_type) LIKE :eventType")
            parameters.addValue("eventType", "%$normalizedType%")
        }
        if (actorFilter != null) {
            predicates.add("LOWER(actor) LIKE :actor")
            parameters.addValue("actor", "%${actorFilter.lowercase()}%")
        }
        if (normalizedOutcome != null) {
            predicates.add("LOWER(outcome) = :outcome")
            parameters.addValue("outcome", normalizedOutcome)
        }
        if (from != null) {
            predicates.add("event_timestamp >= :from")
            parameters.addValue("from", from.toTimestamp())
        }
        if (to != null) {
            predicates.add("event_timestamp <= :to")
            parameters.addValue("to", to.toTimestamp())
        }
        parameters.addValue("limit", limit.coerceIn(1, 1000))

        val whereClause =
            predicates
                .takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "WHERE ", separator = "\n  AND ")
                ?: ""

        return namedParameterJdbcTemplate.query(
            """
            SELECT event_type, actor, outcome, ip_address, details_json, event_timestamp
            FROM auth_audit_events
            $whereClause
            ORDER BY event_timestamp DESC, event_id DESC
            LIMIT :limit
            """.trimIndent(),
            parameters,
            rowMapper,
        )
    }

    fun pruneOlderThan(cutoff: Instant): Int =
        namedParameterJdbcTemplate.update(
            "DELETE FROM auth_audit_events WHERE event_timestamp < :cutoff",
            mapOf("cutoff" to cutoff.toTimestamp()),
        )

    fun clear() {
        namedParameterJdbcTemplate.update("DELETE FROM auth_audit_events", emptyMap<String, Any>())
    }

    private fun ResultSet.toAuthAuditEvent(): AuthAuditEvent =
        AuthAuditEvent(
            type = getString("event_type"),
            actor = getString("actor"),
            outcome = getString("outcome"),
            ipAddress = getString("ip_address"),
            details = objectMapper.readValue(getString("details_json"), detailsType),
            timestamp = getTimestamp("event_timestamp").toInstant(),
        )

    private fun Instant.toTimestamp(): Timestamp = Timestamp.from(this)
}
