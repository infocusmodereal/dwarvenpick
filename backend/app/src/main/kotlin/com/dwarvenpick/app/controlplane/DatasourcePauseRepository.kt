package com.dwarvenpick.app.controlplane

import com.dwarvenpick.app.persistence.PersistenceSchemaInitializer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

@Repository
class DatasourcePauseRepository(
    jdbcTemplate: JdbcTemplate,
    @Suppress("unused") private val persistenceSchemaInitializer: PersistenceSchemaInitializer,
) {
    private val namedParameterJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)

    fun isPaused(datasourceId: String): Boolean {
        clearExpired()
        return namedParameterJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM datasource_pause_state
            WHERE datasource_id = :datasourceId
            """.trimIndent(),
            mapOf("datasourceId" to datasourceId),
            Int::class.java,
        ) == 1
    }

    fun pause(
        datasourceId: String,
        pausedBy: String?,
        reason: String?,
        expiresAt: Instant?,
    ) {
        namedParameterJdbcTemplate.update(
            "DELETE FROM datasource_pause_state WHERE datasource_id = :datasourceId",
            mapOf("datasourceId" to datasourceId),
        )
        namedParameterJdbcTemplate.update(
            """
            INSERT INTO datasource_pause_state (datasource_id, paused_by, reason, created_at, expires_at)
            VALUES (:datasourceId, :pausedBy, :reason, :createdAt, :expiresAt)
            """.trimIndent(),
            mapOf(
                "datasourceId" to datasourceId,
                "pausedBy" to pausedBy,
                "reason" to reason,
                "createdAt" to Timestamp.from(Instant.now()),
                "expiresAt" to expiresAt?.let { Timestamp.from(it) },
            ),
        )
    }

    fun resume(datasourceId: String) {
        namedParameterJdbcTemplate.update(
            "DELETE FROM datasource_pause_state WHERE datasource_id = :datasourceId",
            mapOf("datasourceId" to datasourceId),
        )
    }

    fun clear() {
        namedParameterJdbcTemplate.update("DELETE FROM datasource_pause_state", emptyMap<String, Any>())
    }

    private fun clearExpired() {
        namedParameterJdbcTemplate.update(
            """
            DELETE FROM datasource_pause_state
            WHERE expires_at IS NOT NULL
              AND expires_at <= :now
            """.trimIndent(),
            mapOf("now" to Timestamp.from(Instant.now())),
        )
    }
}
