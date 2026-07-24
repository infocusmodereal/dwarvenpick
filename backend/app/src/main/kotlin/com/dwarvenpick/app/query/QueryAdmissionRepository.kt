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
import java.time.Instant
import javax.sql.DataSource

enum class QueryAdmissionScope(
    val metricValue: String,
) {
    ACTOR("actor"),
    DATASOURCE("datasource"),
    GLOBAL("global"),
}

data class QueryAdmissionLimits(
    val actor: Int,
    val datasource: Int,
    val global: Int,
) {
    init {
        require(actor > 0) { "Actor query admission limit must be positive." }
        require(datasource > 0) { "Datasource query admission limit must be positive." }
        require(global > 0) { "Global query admission limit must be positive." }
    }
}

sealed interface QueryAdmissionDecision {
    data object Admitted : QueryAdmissionDecision

    data object Contended : QueryAdmissionDecision

    data class Limited(
        val scope: QueryAdmissionScope,
        val limit: Int,
    ) : QueryAdmissionDecision
}

internal object QueryAdmissionLockKey {
    const val NAMESPACE = 0x44575051
    const val GLOBAL_NAMESPACE = 0x44575047
    const val GLOBAL_RESOURCE = 1

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

    fun tryReserve(
        record: PersistedQueryRuntimeRecord,
        limits: QueryAdmissionLimits,
        staleCutoff: Instant,
    ): QueryAdmissionDecision =
        if (dialect == QueryAdmissionDialect.POSTGRESQL) {
            executeTransaction(record, limits, staleCutoff, acquireDatabaseLocks = true)
        } else {
            synchronized(h2AdmissionMonitor) {
                executeTransaction(record, limits, staleCutoff, acquireDatabaseLocks = false)
            }
        }

    internal fun metadataDialect(): String = dialect.name

    private fun executeTransaction(
        record: PersistedQueryRuntimeRecord,
        limits: QueryAdmissionLimits,
        staleCutoff: Instant,
        acquireDatabaseLocks: Boolean,
    ): QueryAdmissionDecision =
        requireNotNull(
            transactionTemplate.execute {
                check(TransactionSynchronizationManager.isActualTransactionActive()) {
                    "Query admission requires an active metadata database transaction."
                }
                if (acquireDatabaseLocks && !tryAcquirePostgresLocks(record.actor)) {
                    return@execute QueryAdmissionDecision.Contended
                }
                reserveWithinTransaction(record, limits, staleCutoff)
            },
        )

    private fun tryAcquirePostgresLocks(actor: String): Boolean {
        if (!tryAcquirePostgresLock(QueryAdmissionLockKey.GLOBAL_NAMESPACE, QueryAdmissionLockKey.GLOBAL_RESOURCE)) {
            return false
        }
        return tryAcquirePostgresLock(QueryAdmissionLockKey.NAMESPACE, QueryAdmissionLockKey.forActor(actor))
    }

    private fun tryAcquirePostgresLock(
        namespaceKey: Int,
        resourceKey: Int,
    ): Boolean =
        namedParameterJdbcTemplate.queryForObject(
            "SELECT pg_try_advisory_xact_lock(:namespaceKey, :resourceKey)",
            mapOf(
                "namespaceKey" to namespaceKey,
                "resourceKey" to resourceKey,
            ),
            Boolean::class.java,
        ) == true

    private fun reserveWithinTransaction(
        record: PersistedQueryRuntimeRecord,
        limits: QueryAdmissionLimits,
        staleCutoff: Instant,
    ): QueryAdmissionDecision {
        queryRuntimeRepository.markStaleActiveExecutions(
            staleCutoff,
            "Query state was lost after its backend stopped heartbeating.",
            maxRows = ADMISSION_STALE_CLEANUP_LIMIT,
        )

        val activeStatuses = listOf(QueryExecutionStatus.QUEUED.name, QueryExecutionStatus.RUNNING.name)
        if (countActive("actor = :actor", mapOf("actor" to record.actor), activeStatuses) >= limits.actor) {
            return QueryAdmissionDecision.Limited(QueryAdmissionScope.ACTOR, limits.actor)
        }
        if (
            countActive(
                "datasource_id = :datasourceId",
                mapOf("datasourceId" to record.datasourceId),
                activeStatuses,
            ) >= limits.datasource
        ) {
            return QueryAdmissionDecision.Limited(QueryAdmissionScope.DATASOURCE, limits.datasource)
        }
        if (countActive("1 = 1", emptyMap(), activeStatuses) >= limits.global) {
            return QueryAdmissionDecision.Limited(QueryAdmissionScope.GLOBAL, limits.global)
        }

        queryRuntimeRepository.insertInitial(record)
        return QueryAdmissionDecision.Admitted
    }

    private fun countActive(
        scopePredicate: String,
        scopeParameters: Map<String, Any>,
        activeStatuses: List<String>,
    ): Int =
        namedParameterJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM query_runtime_executions
            WHERE $scopePredicate
              AND status IN (:activeStatuses)
            """.trimIndent(),
            scopeParameters + ("activeStatuses" to activeStatuses),
            Int::class.java,
        ) ?: 0

    private enum class QueryAdmissionDialect {
        POSTGRESQL,
        LOCAL,
        ;

        companion object {
            fun fromProductName(productName: String): QueryAdmissionDialect =
                if (productName.equals("PostgreSQL", ignoreCase = true)) POSTGRESQL else LOCAL
        }
    }

    private companion object {
        const val ADMISSION_STALE_CLEANUP_LIMIT = 50
    }
}
