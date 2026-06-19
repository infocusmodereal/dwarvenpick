package com.dwarvenpick.app.datasource

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

data class PoolMetricsSnapshot(
    val instanceId: String,
    val key: String,
    val datasourceId: String,
    val credentialProfile: String,
    val activeConnections: Int,
    val idleConnections: Int,
    val totalConnections: Int,
    val maximumPoolSize: Int,
    val threadsAwaitingConnection: Int,
    val updatedAt: Instant,
)

@Repository
class PoolMetricsSnapshotRepository(
    jdbcTemplate: JdbcTemplate,
) {
    private val namedParameterJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
    private val rowMapper = RowMapper { resultSet, _ -> resultSet.toSnapshot() }

    @Transactional
    fun replaceForInstance(
        instanceId: String,
        snapshots: List<PoolMetricsSnapshot>,
        updatedAt: Instant,
    ) {
        namedParameterJdbcTemplate.update(
            "DELETE FROM datasource_pool_metrics WHERE instance_id = :instanceId",
            mapOf("instanceId" to instanceId),
        )
        snapshots.forEach { snapshot ->
            namedParameterJdbcTemplate.update(insertSql, snapshot.copy(updatedAt = updatedAt).toParameters())
        }
    }

    fun listRecent(cutoff: Instant): List<PoolMetricsSnapshot> =
        namedParameterJdbcTemplate.query(
            """
            SELECT instance_id, pool_key, datasource_id, credential_profile, active_connections, idle_connections,
                   total_connections, maximum_pool_size, threads_awaiting_connection, updated_at
            FROM datasource_pool_metrics
            WHERE updated_at >= :cutoff
            ORDER BY datasource_id, credential_profile, instance_id
            """.trimIndent(),
            mapOf("cutoff" to cutoff.toTimestamp()),
            rowMapper,
        )

    fun deleteOlderThan(cutoff: Instant): Int =
        namedParameterJdbcTemplate.update(
            "DELETE FROM datasource_pool_metrics WHERE updated_at < :cutoff",
            mapOf("cutoff" to cutoff.toTimestamp()),
        )

    fun clear() {
        namedParameterJdbcTemplate.update("DELETE FROM datasource_pool_metrics", emptyMap<String, Any>())
    }

    private fun PoolMetricsSnapshot.toParameters(): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("instanceId", instanceId)
            .addValue("poolKey", key)
            .addValue("datasourceId", datasourceId)
            .addValue("credentialProfile", credentialProfile)
            .addValue("activeConnections", activeConnections)
            .addValue("idleConnections", idleConnections)
            .addValue("totalConnections", totalConnections)
            .addValue("maximumPoolSize", maximumPoolSize)
            .addValue("threadsAwaitingConnection", threadsAwaitingConnection)
            .addValue("updatedAt", updatedAt.toTimestamp())

    private fun ResultSet.toSnapshot(): PoolMetricsSnapshot =
        PoolMetricsSnapshot(
            instanceId = getString("instance_id"),
            key = getString("pool_key"),
            datasourceId = getString("datasource_id"),
            credentialProfile = getString("credential_profile"),
            activeConnections = getInt("active_connections"),
            idleConnections = getInt("idle_connections"),
            totalConnections = getInt("total_connections"),
            maximumPoolSize = getInt("maximum_pool_size"),
            threadsAwaitingConnection = getInt("threads_awaiting_connection"),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    private fun Instant.toTimestamp(): Timestamp = Timestamp.from(this)

    private companion object {
        val insertSql =
            """
            INSERT INTO datasource_pool_metrics (
              instance_id, pool_key, datasource_id, credential_profile, active_connections, idle_connections,
              total_connections, maximum_pool_size, threads_awaiting_connection, updated_at
            ) VALUES (
              :instanceId, :poolKey, :datasourceId, :credentialProfile, :activeConnections, :idleConnections,
              :totalConnections, :maximumPoolSize, :threadsAwaitingConnection, :updatedAt
            )
            """.trimIndent()
    }
}
