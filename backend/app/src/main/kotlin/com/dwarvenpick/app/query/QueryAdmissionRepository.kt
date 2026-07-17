package com.dwarvenpick.app.query

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.sql.DataSource

enum class QueryAdmissionResult {
    ADMITTED,
    LIMIT_REACHED,
    GLOBAL_LIMIT_REACHED,
    DATASOURCE_LIMIT_REACHED,
}

data class QueryAdmissionLimits(
    val actor: Int,
    val global: Int,
    val datasource: Int,
)

internal object QueryAdmissionLockKey {
    const val NAMESPACE = 0x44575051
    const val GLOBAL = 0x474C4F42

    fun forActor(actor: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(actor.toByteArray(StandardCharsets.UTF_8))
        return ByteBuffer.wrap(digest, 0, Int.SIZE_BYTES).int
    }
}

@Repository
class QueryAdmissionRepository(
    jdbcTemplate: JdbcTemplate,
    dataSource: DataSource,
    transactionManager: PlatformTransactionManager,
    private val queryRuntimeRepository: QueryRuntimeRepository,
    @Value("\${dwarvenpick.query.admission-timeout-seconds:10}") admissionTimeoutSeconds: Int,
) {
    private val namedParameterJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
    private val transactionTemplate =
        TransactionTemplate(transactionManager).apply {
            timeout = admissionTimeoutSeconds.coerceAtLeast(1)
        }
    private val h2AdmissionMonitor = Any()
    private val dialect = QueryAdmissionDialect.fromProductName(dataSource.connection.use { it.metaData.databaseProductName })

    fun reserve(
        record: PersistedQueryRuntimeRecord,
        limits: QueryAdmissionLimits,
    ): QueryAdmissionResult =
        if (dialect == QueryAdmissionDialect.POSTGRESQL) {
            executeTransaction(record, limits, acquireDatabaseLock = true)
        } else {
            synchronized(h2AdmissionMonitor) {
                executeTransaction(record, limits, acquireDatabaseLock = false)
            }
        }

    internal fun metadataDialect(): String = dialect.name

    private fun executeTransaction(
        record: PersistedQueryRuntimeRecord,
        limits: QueryAdmissionLimits,
        acquireDatabaseLock: Boolean,
    ): QueryAdmissionResult =
        requireNotNull(
            transactionTemplate.execute {
                check(TransactionSynchronizationManager.isActualTransactionActive()) {
                    "Query admission requires an active metadata database transaction."
                }
                if (acquireDatabaseLock) {
                    acquirePostgresLock(QueryAdmissionLockKey.GLOBAL)
                    acquirePostgresActorLock(record.actor)
                }
                reserveWithinTransaction(record, limits)
            },
        )

    private fun acquirePostgresActorLock(actor: String) {
        acquirePostgresLock(QueryAdmissionLockKey.forActor(actor))
    }

    private fun acquirePostgresLock(lockKey: Int) {
        namedParameterJdbcTemplate.query(
            "SELECT pg_advisory_xact_lock(:namespaceKey, :lockKey)",
            mapOf(
                "namespaceKey" to QueryAdmissionLockKey.NAMESPACE,
                "lockKey" to lockKey,
            ),
        ) { _, _ -> Unit }
    }

    private fun reserveWithinTransaction(
        record: PersistedQueryRuntimeRecord,
        limits: QueryAdmissionLimits,
    ): QueryAdmissionResult {
        val parameters =
            mapOf(
                "actor" to record.actor,
                "datasourceId" to record.datasourceId,
                "activeStatuses" to listOf(QueryExecutionStatus.QUEUED.name, QueryExecutionStatus.RUNNING.name),
            )
        val counts =
            namedParameterJdbcTemplate.queryForObject(
                """
                SELECT
                  COUNT(*) AS global_count,
                  COALESCE(SUM(CASE WHEN actor = :actor THEN 1 ELSE 0 END), 0) AS actor_count,
                  COALESCE(SUM(CASE WHEN datasource_id = :datasourceId THEN 1 ELSE 0 END), 0) AS datasource_count
                FROM query_runtime_executions
                WHERE status IN (:activeStatuses)
                """.trimIndent(),
                parameters,
            ) { resultSet, _ ->
                AdmissionCounts(
                    global = resultSet.getInt("global_count"),
                    actor = resultSet.getInt("actor_count"),
                    datasource = resultSet.getInt("datasource_count"),
                )
            } ?: AdmissionCounts(global = 0, actor = 0, datasource = 0)

        if (counts.actor >= limits.actor.coerceAtLeast(1)) {
            return QueryAdmissionResult.LIMIT_REACHED
        }
        if (counts.global >= limits.global.coerceAtLeast(1)) {
            return QueryAdmissionResult.GLOBAL_LIMIT_REACHED
        }
        if (counts.datasource >= limits.datasource.coerceAtLeast(1)) {
            return QueryAdmissionResult.DATASOURCE_LIMIT_REACHED
        }

        queryRuntimeRepository.insertInitial(record)
        return QueryAdmissionResult.ADMITTED
    }

    private data class AdmissionCounts(
        val global: Int,
        val actor: Int,
        val datasource: Int,
    )

    private enum class QueryAdmissionDialect {
        POSTGRESQL,
        LOCAL,
        ;

        companion object {
            fun fromProductName(productName: String): QueryAdmissionDialect =
                if (productName.equals("PostgreSQL", ignoreCase = true)) POSTGRESQL else LOCAL
        }
    }
}
