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
}

internal object QueryAdmissionLockKey {
    const val NAMESPACE = 0x44575051

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
        concurrencyLimit: Int,
    ): QueryAdmissionResult =
        if (dialect == QueryAdmissionDialect.POSTGRESQL) {
            executeTransaction(record, concurrencyLimit, acquireDatabaseLock = true)
        } else {
            synchronized(h2AdmissionMonitor) {
                executeTransaction(record, concurrencyLimit, acquireDatabaseLock = false)
            }
        }

    internal fun metadataDialect(): String = dialect.name

    private fun executeTransaction(
        record: PersistedQueryRuntimeRecord,
        concurrencyLimit: Int,
        acquireDatabaseLock: Boolean,
    ): QueryAdmissionResult =
        requireNotNull(
            transactionTemplate.execute {
                check(TransactionSynchronizationManager.isActualTransactionActive()) {
                    "Query admission requires an active metadata database transaction."
                }
                if (acquireDatabaseLock) {
                    acquirePostgresActorLock(record.actor)
                }
                reserveWithinTransaction(record, concurrencyLimit)
            },
        )

    private fun acquirePostgresActorLock(actor: String) {
        namedParameterJdbcTemplate.query(
            "SELECT pg_advisory_xact_lock(:namespaceKey, :actorKey)",
            mapOf(
                "namespaceKey" to QueryAdmissionLockKey.NAMESPACE,
                "actorKey" to QueryAdmissionLockKey.forActor(actor),
            ),
        ) { _, _ -> Unit }
    }

    private fun reserveWithinTransaction(
        record: PersistedQueryRuntimeRecord,
        concurrencyLimit: Int,
    ): QueryAdmissionResult {
        val activeExecutions =
            namedParameterJdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM query_runtime_executions
                WHERE actor = :actor
                  AND status IN (:activeStatuses)
                """.trimIndent(),
                mapOf(
                    "actor" to record.actor,
                    "activeStatuses" to listOf(QueryExecutionStatus.QUEUED.name, QueryExecutionStatus.RUNNING.name),
                ),
                Int::class.java,
            ) ?: 0

        if (activeExecutions >= concurrencyLimit) {
            return QueryAdmissionResult.LIMIT_REACHED
        }

        queryRuntimeRepository.insertInitial(record)
        return QueryAdmissionResult.ADMITTED
    }

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
