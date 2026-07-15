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
    ACTOR_LIMIT_REACHED,
    AGGREGATE_LIMIT_REACHED,
    DATASOURCE_LIMIT_REACHED,
}

data class QueryAdmissionLimits(
    val actor: Int,
    val aggregate: Int,
    val datasource: Int,
)

internal object QueryAdmissionLockKey {
    const val NAMESPACE = 0x44575051

    fun forActor(actor: String): Int = forScope("actor:$actor")

    fun forScope(scope: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(StandardCharsets.UTF_8))
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
                    acquirePostgresLock("aggregate")
                    acquirePostgresLock("datasource:${record.datasourceId}")
                    acquirePostgresLock("actor:${record.actor}")
                }
                reserveWithinTransaction(record, limits)
            },
        )

    private fun acquirePostgresLock(scope: String) {
        namedParameterJdbcTemplate.query(
            "SELECT pg_advisory_xact_lock(:namespaceKey, :actorKey)",
            mapOf(
                "namespaceKey" to QueryAdmissionLockKey.NAMESPACE,
                "actorKey" to QueryAdmissionLockKey.forScope(scope),
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
        val aggregateExecutions = countActiveExecutions("", parameters)
        if (aggregateExecutions >= limits.aggregate.coerceAtLeast(1)) {
            return QueryAdmissionResult.AGGREGATE_LIMIT_REACHED
        }

        val datasourceExecutions = countActiveExecutions("AND datasource_id = :datasourceId", parameters)
        if (datasourceExecutions >= limits.datasource.coerceAtLeast(1)) {
            return QueryAdmissionResult.DATASOURCE_LIMIT_REACHED
        }

        val actorExecutions = countActiveExecutions("AND actor = :actor", parameters)
        if (actorExecutions >= limits.actor.coerceAtLeast(1)) {
            return QueryAdmissionResult.ACTOR_LIMIT_REACHED
        }

        queryRuntimeRepository.insertInitial(record)
        return QueryAdmissionResult.ADMITTED
    }

    private fun countActiveExecutions(
        predicate: String,
        parameters: Map<String, Any>,
    ): Int =
        namedParameterJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM query_runtime_executions
            WHERE status IN (:activeStatuses)
            $predicate
            """.trimIndent(),
            parameters,
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
}
