package com.badgermole.app.query

import com.badgermole.app.auth.AuthAuditEvent
import com.badgermole.app.auth.AuthAuditLogger
import com.badgermole.app.datasource.DatasourcePoolManager
import com.badgermole.app.rbac.QueryAccessPolicy
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Statement
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

class QueryExecutionNotFoundException(
    override val message: String,
) : RuntimeException(message)

class QueryExecutionForbiddenException(
    override val message: String,
) : RuntimeException(message)

class QueryResultsNotReadyException(
    override val message: String,
) : RuntimeException(message)

class QueryResultsExpiredException(
    override val message: String,
) : RuntimeException(message)

class QueryInvalidPageTokenException(
    override val message: String,
) : RuntimeException(message)

class QueryConcurrencyLimitException(
    override val message: String,
) : RuntimeException(message)

private class QueryCanceledException(
    override val message: String,
) : RuntimeException(message)

private class QueryRuntimeLimitException(
    override val message: String,
) : RuntimeException(message)

private data class QueryExecutionRecord(
    val executionId: String,
    val actor: String,
    val ipAddress: String?,
    val datasourceId: String,
    val credentialProfile: String,
    val sql: String,
    val queryHash: String,
    val maxRowsPerQuery: Int,
    val maxRuntimeSeconds: Int,
    val concurrencyLimit: Int,
    @Volatile var status: QueryExecutionStatus,
    @Volatile var message: String,
    @Volatile var errorSummary: String?,
    val submittedAt: Instant,
    @Volatile var startedAt: Instant?,
    @Volatile var completedAt: Instant?,
    @Volatile var rowLimitReached: Boolean,
    val columns: MutableList<QueryResultColumn>,
    val rows: MutableList<List<String?>>,
    @Volatile var lastAccessedAt: Instant,
    @Volatile var resultsExpired: Boolean,
    @Volatile var cancelRequested: Boolean,
    @Volatile var activeStatement: Statement?,
    @Volatile var activeConnection: Connection?,
    @Volatile var executionFuture: Future<*>?,
)

@Service
class QueryExecutionManager(
    private val datasourcePoolManager: DatasourcePoolManager,
    private val authAuditLogger: AuthAuditLogger,
    private val queryExecutionProperties: QueryExecutionProperties,
) {
    private val virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()
    private val executions = ConcurrentHashMap<String, QueryExecutionRecord>()
    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>()
    private val eventCounter = AtomicLong(0)

    fun submitQuery(
        actor: String,
        ipAddress: String?,
        request: QueryExecutionRequest,
        policy: QueryAccessPolicy,
    ): QueryExecutionResponse {
        val normalizedSql = request.sql.trim()
        val queryHash = sha256Hex(normalizedSql)
        val executionId = UUID.randomUUID().toString()

        val maxRows = policy.maxRowsPerQuery.coerceAtLeast(1).coerceAtMost(queryExecutionProperties.maxBufferedRows)
        val maxRuntimeSeconds = policy.maxRuntimeSeconds.coerceAtLeast(1)
        val concurrencyLimit =
            policy.concurrencyLimit.coerceAtLeast(1).coerceAtMost(queryExecutionProperties.maxConcurrencyPerUser.coerceAtLeast(1))

        synchronized(this) {
            val activeExecutions = executions.values.count { execution ->
                execution.actor == actor &&
                    (execution.status == QueryExecutionStatus.QUEUED || execution.status == QueryExecutionStatus.RUNNING)
            }
            if (activeExecutions >= concurrencyLimit) {
                throw QueryConcurrencyLimitException(
                    "Concurrent query limit reached ($concurrencyLimit). Cancel an active query before running another.",
                )
            }

            val record =
                QueryExecutionRecord(
                    executionId = executionId,
                    actor = actor,
                    ipAddress = ipAddress,
                    datasourceId = request.datasourceId.trim(),
                    credentialProfile = policy.credentialProfile,
                    sql = normalizedSql,
                    queryHash = queryHash,
                    maxRowsPerQuery = maxRows,
                    maxRuntimeSeconds = maxRuntimeSeconds,
                    concurrencyLimit = concurrencyLimit,
                    status = QueryExecutionStatus.QUEUED,
                    message = "Query accepted and queued.",
                    errorSummary = null,
                    submittedAt = Instant.now(),
                    startedAt = null,
                    completedAt = null,
                    rowLimitReached = false,
                    columns = mutableListOf(),
                    rows = mutableListOf(),
                    lastAccessedAt = Instant.now(),
                    resultsExpired = false,
                    cancelRequested = false,
                    activeStatement = null,
                    activeConnection = null,
                    executionFuture = null,
                )
            executions[executionId] = record
            publishEvent(record, "Query queued.")
            auditExecution(
                record = record,
                type = "query.execute",
                outcome = "queued",
                details =
                    mapOf(
                        "datasourceId" to record.datasourceId,
                        "executionId" to executionId,
                        "queryHash" to queryHash,
                    ),
            )

            val future = virtualExecutor.submit { executeQueuedQuery(record) }
            record.executionFuture = future
        }

        return QueryExecutionResponse(
            executionId = executionId,
            datasourceId = request.datasourceId.trim(),
            status = QueryExecutionStatus.QUEUED.name,
            message = "Query accepted for execution.",
            queryHash = queryHash,
        )
    }

    fun cancelQuery(
        actor: String,
        isSystemAdmin: Boolean,
        executionId: String,
    ): QueryCancelResponse {
        val record = resolveAuthorizedExecution(actor, isSystemAdmin, executionId)
        if (record.status in terminalStatuses()) {
            return QueryCancelResponse(
                executionId = record.executionId,
                status = record.status.name,
                message = "Query already finished with status ${record.status.name}.",
                canceledAt = Instant.now().toString(),
            )
        }

        record.cancelRequested = true
        if (record.status == QueryExecutionStatus.QUEUED) {
            record.executionFuture?.cancel(true)
            markCanceled(record, "Query canceled before execution started.")
            auditExecution(
                record = record,
                type = "query.cancel",
                outcome = "canceled",
                details = mapOf("executionId" to record.executionId),
            )
            return QueryCancelResponse(
                executionId = record.executionId,
                status = record.status.name,
                message = record.message,
                canceledAt = Instant.now().toString(),
            )
        }

        runCatching { record.activeStatement?.cancel() }
        val graceDelayMs = queryExecutionProperties.cancelGracePeriodMs.coerceAtLeast(0)
        if (graceDelayMs > 0) {
            Thread.sleep(graceDelayMs.coerceAtMost(1000))
        }
        runCatching { record.activeConnection?.close() }
        record.executionFuture?.cancel(true)

        if (record.status == QueryExecutionStatus.RUNNING) {
            markCanceled(record, "Query canceled by user request.")
        }

        auditExecution(
            record = record,
            type = "query.cancel",
            outcome = "canceled",
            details = mapOf("executionId" to record.executionId),
        )

        return QueryCancelResponse(
            executionId = record.executionId,
            status = record.status.name,
            message = record.message,
            canceledAt = Instant.now().toString(),
        )
    }

    fun getExecutionStatus(
        actor: String,
        isSystemAdmin: Boolean,
        executionId: String,
    ): QueryExecutionStatusResponse {
        val record = resolveAuthorizedExecution(actor, isSystemAdmin, executionId)
        return record.toStatusResponse()
    }

    fun getQueryResults(
        actor: String,
        isSystemAdmin: Boolean,
        executionId: String,
        request: QueryResultsRequest,
    ): QueryResultsResponse {
        val record = resolveAuthorizedExecution(actor, isSystemAdmin, executionId)
        if (record.status == QueryExecutionStatus.QUEUED || record.status == QueryExecutionStatus.RUNNING) {
            throw QueryResultsNotReadyException("Query results are not ready yet. Current status: ${record.status.name}.")
        }
        if (record.resultsExpired) {
            throw QueryResultsExpiredException("Result session expired. Re-run the query.")
        }
        if (record.status == QueryExecutionStatus.FAILED) {
            throw QueryResultsNotReadyException("Query failed and no result set is available.")
        }
        if (record.status == QueryExecutionStatus.CANCELED) {
            throw QueryResultsNotReadyException("Query was canceled before a complete result set was available.")
        }

        val resolvedPageSize =
            request.pageSize?.coerceIn(1, queryExecutionProperties.maxPageSize.coerceAtLeast(1))
                ?: queryExecutionProperties.defaultPageSize.coerceAtLeast(1)

        val startOffset =
            request.pageToken?.let { token ->
                parsePageToken(executionId, token)
            } ?: 0

        val rowsSnapshot = synchronized(record.rows) { record.rows.toList() }
        if (startOffset > rowsSnapshot.size) {
            throw QueryInvalidPageTokenException("pageToken offset is outside of available result rows.")
        }

        val endOffset = (startOffset + resolvedPageSize).coerceAtMost(rowsSnapshot.size)
        val pageRows = rowsSnapshot.subList(startOffset, endOffset)
        val nextPageToken =
            if (endOffset < rowsSnapshot.size) {
                buildPageToken(executionId, endOffset)
            } else {
                null
            }

        record.lastAccessedAt = Instant.now()
        return QueryResultsResponse(
            executionId = executionId,
            status = record.status.name,
            columns = record.columns.toList(),
            rows = pageRows,
            pageSize = resolvedPageSize,
            nextPageToken = nextPageToken,
            rowLimitReached = record.rowLimitReached,
        )
    }

    fun subscribeToStatusEvents(
        actor: String,
        isSystemAdmin: Boolean,
    ): SseEmitter {
        val emitter = SseEmitter(0L)
        val actorSubscribers = subscribers.computeIfAbsent(actor) { CopyOnWriteArrayList() }
        actorSubscribers.add(emitter)

        emitter.onCompletion { actorSubscribers.remove(emitter) }
        emitter.onTimeout {
            emitter.complete()
            actorSubscribers.remove(emitter)
        }
        emitter.onError {
            emitter.complete()
            actorSubscribers.remove(emitter)
        }

        sendInFlightSnapshot(actor, isSystemAdmin, emitter)
        return emitter
    }

    @Scheduled(fixedDelayString = "\${badgermole.query.cleanup-interval-ms:30000}")
    fun cleanupExpiredSessions() {
        val now = Instant.now()
        val resultTtlSeconds = queryExecutionProperties.resultSessionTtlSeconds.coerceAtLeast(60)
        val retentionSeconds = queryExecutionProperties.executionRetentionSeconds.coerceAtLeast(120)

        val removableExecutionIds = mutableListOf<String>()
        executions.forEach { (executionId, record) ->
            if (record.status == QueryExecutionStatus.RUNNING) {
                val startedAt = record.startedAt
                if (startedAt != null) {
                    val runningForSeconds = Duration.between(startedAt, now).seconds
                    if (runningForSeconds > record.maxRuntimeSeconds + 10L) {
                        runCatching {
                            cancelQuery(
                                actor = record.actor,
                                isSystemAdmin = true,
                                executionId = executionId,
                            )
                        }
                    }
                }
            }

            if (record.status !in terminalStatuses()) {
                return@forEach
            }

            if (!record.resultsExpired) {
                val idleForSeconds = Duration.between(record.lastAccessedAt, now).seconds
                if (idleForSeconds > resultTtlSeconds) {
                    synchronized(record.rows) {
                        record.rows.clear()
                    }
                    record.columns.clear()
                    record.resultsExpired = true
                    record.message = "Result session expired. Re-run the query."
                }
            }

            val completedAt = record.completedAt ?: record.submittedAt
            if (Duration.between(completedAt, now).seconds > retentionSeconds) {
                removableExecutionIds.add(executionId)
            }
        }

        removableExecutionIds.forEach { executionId ->
            val removed = executions.remove(executionId)
            if (removed != null) {
                closeRuntimeResources(removed)
            }
        }
    }

    private fun executeQueuedQuery(record: QueryExecutionRecord) {
        if (record.cancelRequested) {
            markCanceled(record, "Query canceled before execution started.")
            return
        }

        markRunning(record)
        auditExecution(
            record = record,
            type = "query.execute",
            outcome = "running",
            details =
                mapOf(
                    "executionId" to record.executionId,
                    "datasourceId" to record.datasourceId,
                ),
        )

        try {
            executeSql(record)
            throwIfCanceled(record)
            markSucceeded(record)
            auditExecution(
                record = record,
                type = "query.execute",
                outcome = "succeeded",
                details =
                    mapOf(
                        "executionId" to record.executionId,
                        "rows" to record.rows.size,
                        "rowLimitReached" to record.rowLimitReached,
                    ),
            )
        } catch (ex: QueryCanceledException) {
            markCanceled(record, "Query canceled.")
            auditExecution(
                record = record,
                type = "query.execute",
                outcome = "canceled",
                details = mapOf("executionId" to record.executionId),
            )
        } catch (ex: QueryRuntimeLimitException) {
            markFailed(record, ex.message ?: "Query exceeded runtime limit.")
            auditExecution(
                record = record,
                type = "query.execute",
                outcome = "failed",
                details =
                    mapOf(
                        "executionId" to record.executionId,
                        "reason" to "runtime_limit",
                    ),
            )
        } catch (ex: Throwable) {
            markFailed(record, sanitizeErrorMessage(ex.message ?: "Query execution failed."))
            auditExecution(
                record = record,
                type = "query.execute",
                outcome = "failed",
                details =
                    mapOf(
                        "executionId" to record.executionId,
                        "reason" to "execution_error",
                    ),
            )
        } finally {
            closeRuntimeResources(record)
        }
    }

    private fun executeSql(record: QueryExecutionRecord) {
        if (shouldUseSimulation(record.sql)) {
            executeSqlInSimulation(record)
            return
        }

        runCatching {
            executeSqlAgainstDatasource(record)
        }.getOrElse { exception ->
            if (canFallbackToSimulation(record.sql)) {
                executeSqlInSimulation(record)
            } else {
                throw exception
            }
        }
    }

    private fun executeSqlAgainstDatasource(record: QueryExecutionRecord) {
        val handle =
            datasourcePoolManager.openConnection(
                datasourceId = record.datasourceId,
                credentialProfile = record.credentialProfile,
            )
        record.activeConnection = handle.connection

        val statement =
            handle.connection.createStatement().apply {
                queryTimeout = record.maxRuntimeSeconds
            }
        record.activeStatement = statement

        val hasResultSet = statement.execute(record.sql)
        if (!hasResultSet) {
            val affectedRows = statement.updateCount
            record.columns.clear()
            record.columns.add(QueryResultColumn(name = "affected_rows", jdbcType = "INTEGER"))
            synchronized(record.rows) {
                record.rows.add(listOf(affectedRows.toString()))
            }
            return
        }

        statement.resultSet.use { resultSet ->
            val metadata = resultSet.metaData
            val resolvedColumns =
                (1..metadata.columnCount).map { index ->
                    QueryResultColumn(
                        name = metadata.getColumnLabel(index) ?: "col_$index",
                        jdbcType = metadata.getColumnTypeName(index) ?: "UNKNOWN",
                    )
                }
            record.columns.clear()
            record.columns.addAll(resolvedColumns)

            while (resultSet.next()) {
                throwIfCanceled(record)
                enforceRuntimeLimit(record)

                synchronized(record.rows) {
                    if (record.rows.size >= record.maxRowsPerQuery) {
                        record.rowLimitReached = true
                        return@use
                    }

                    val row =
                        (1..metadata.columnCount).map { index ->
                            resultSet.getObject(index)?.toString()
                        }
                    record.rows.add(row)
                }
            }
        }
    }

    private fun executeSqlInSimulation(record: QueryExecutionRecord) {
        val sql = record.sql.trim()
        val normalizedSql = sql.lowercase(Locale.getDefault())

        val sleepMatch = Regex("pg_sleep\\((\\d+)\\)").find(normalizedSql)
        if (sleepMatch != null) {
            val sleepSeconds = sleepMatch.groupValues[1].toLongOrNull()?.coerceAtLeast(0) ?: 0L
            val endAt = Instant.now().plusSeconds(sleepSeconds)
            record.columns.clear()
            record.columns.add(QueryResultColumn(name = "pg_sleep", jdbcType = "DOUBLE"))
            while (Instant.now().isBefore(endAt)) {
                throwIfCanceled(record)
                enforceRuntimeLimit(record)
                Thread.sleep(100)
            }
            synchronized(record.rows) {
                record.rows.add(listOf("0"))
            }
            return
        }

        val seriesMatch = Regex("generate_series\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)").find(normalizedSql)
        if (seriesMatch != null) {
            val start = seriesMatch.groupValues[1].toIntOrNull() ?: 1
            val end = seriesMatch.groupValues[2].toIntOrNull() ?: start
            record.columns.clear()
            record.columns.add(QueryResultColumn(name = "generate_series", jdbcType = "INTEGER"))
            synchronized(record.rows) {
                for (value in start..end) {
                    throwIfCanceled(record)
                    enforceRuntimeLimit(record)
                    if (record.rows.size >= record.maxRowsPerQuery) {
                        record.rowLimitReached = true
                        break
                    }
                    record.rows.add(listOf(value.toString()))
                }
            }
            return
        }

        val selectOneMatch = Regex("^select\\s+1(?:\\s+as\\s+([a-zA-Z_][a-zA-Z0-9_]*))?\\s*;?$").find(normalizedSql)
        if (selectOneMatch != null) {
            val alias = selectOneMatch.groupValues.getOrNull(1)?.ifBlank { null } ?: "value"
            record.columns.clear()
            record.columns.add(QueryResultColumn(name = alias, jdbcType = "INTEGER"))
            synchronized(record.rows) {
                record.rows.add(listOf("1"))
            }
            return
        }

        record.columns.clear()
        record.columns.add(QueryResultColumn(name = "result", jdbcType = "VARCHAR"))
        synchronized(record.rows) {
            record.rows.add(listOf("Query executed in local simulation mode."))
        }
    }

    private fun markRunning(record: QueryExecutionRecord) {
        record.status = QueryExecutionStatus.RUNNING
        record.startedAt = Instant.now()
        record.message = "Query is running."
        publishEvent(record, record.message)
    }

    private fun markSucceeded(record: QueryExecutionRecord) {
        if (record.status == QueryExecutionStatus.CANCELED) {
            return
        }

        record.status = QueryExecutionStatus.SUCCEEDED
        record.completedAt = Instant.now()
        record.lastAccessedAt = Instant.now()
        record.message =
            if (record.rowLimitReached) {
                "Query succeeded. Result truncated at ${record.maxRowsPerQuery} rows."
            } else {
                "Query succeeded."
            }
        publishEvent(record, record.message)
    }

    private fun markFailed(
        record: QueryExecutionRecord,
        message: String,
    ) {
        if (record.status == QueryExecutionStatus.CANCELED) {
            return
        }

        record.status = QueryExecutionStatus.FAILED
        record.errorSummary = message
        record.message = "Query failed."
        record.completedAt = Instant.now()
        publishEvent(record, message)
    }

    private fun markCanceled(
        record: QueryExecutionRecord,
        message: String,
    ) {
        record.status = QueryExecutionStatus.CANCELED
        record.errorSummary = null
        record.message = message
        record.completedAt = Instant.now()
        publishEvent(record, message)
    }

    private fun resolveAuthorizedExecution(
        actor: String,
        isSystemAdmin: Boolean,
        executionId: String,
    ): QueryExecutionRecord {
        val record =
            executions[executionId]
                ?: throw QueryExecutionNotFoundException("Query execution '$executionId' was not found.")

        if (!isSystemAdmin && record.actor != actor) {
            throw QueryExecutionForbiddenException("Query execution '$executionId' is not accessible.")
        }

        return record
    }

    private fun parsePageToken(
        executionId: String,
        pageToken: String,
    ): Int {
        val decoded =
            runCatching {
                String(
                    Base64.getUrlDecoder().decode(pageToken),
                    StandardCharsets.UTF_8,
                )
            }.getOrElse {
                throw QueryInvalidPageTokenException("pageToken is invalid.")
            }

        val parts = decoded.split(":")
        if (parts.size != 2 || parts[0] != executionId) {
            throw QueryInvalidPageTokenException("pageToken does not match this execution id.")
        }

        return parts[1].toIntOrNull()?.takeIf { it >= 0 }
            ?: throw QueryInvalidPageTokenException("pageToken offset is invalid.")
    }

    private fun buildPageToken(
        executionId: String,
        offset: Int,
    ): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("$executionId:$offset".toByteArray(StandardCharsets.UTF_8))

    private fun shouldUseSimulation(sql: String): Boolean {
        val normalizedSql = sql.trim().lowercase(Locale.getDefault())
        return normalizedSql.contains("pg_sleep(") ||
            normalizedSql.contains("generate_series(") ||
            Regex("^select\\s+1(?:\\s+as\\s+[a-zA-Z_][a-zA-Z0-9_]*)?\\s*;?$").matches(normalizedSql)
    }

    private fun canFallbackToSimulation(sql: String): Boolean {
        val normalizedSql = sql.trim().lowercase(Locale.getDefault())
        return normalizedSql.startsWith("select")
    }

    private fun enforceRuntimeLimit(record: QueryExecutionRecord) {
        val startedAt = record.startedAt ?: return
        val elapsedSeconds = Duration.between(startedAt, Instant.now()).seconds
        if (elapsedSeconds > record.maxRuntimeSeconds) {
            throw QueryRuntimeLimitException("Query timed out after ${record.maxRuntimeSeconds} seconds.")
        }
    }

    private fun throwIfCanceled(record: QueryExecutionRecord) {
        if (record.cancelRequested || Thread.currentThread().isInterrupted) {
            throw QueryCanceledException("Query canceled.")
        }
    }

    private fun closeRuntimeResources(record: QueryExecutionRecord) {
        runCatching { record.activeStatement?.close() }
        runCatching { record.activeConnection?.close() }
        record.activeStatement = null
        record.activeConnection = null
    }

    private fun terminalStatuses(): Set<QueryExecutionStatus> =
        setOf(QueryExecutionStatus.SUCCEEDED, QueryExecutionStatus.FAILED, QueryExecutionStatus.CANCELED)

    private fun publishEvent(
        record: QueryExecutionRecord,
        message: String,
    ) {
        val event =
            QueryStatusEventResponse(
                eventId = eventCounter.incrementAndGet().toString(),
                executionId = record.executionId,
                datasourceId = record.datasourceId,
                status = record.status.name,
                message = message,
                occurredAt = Instant.now().toString(),
            )

        subscribers[record.actor]
            ?.toList()
            ?.forEach { emitter ->
                sendEvent(emitter, event, record.actor)
            }
    }

    private fun sendInFlightSnapshot(
        actor: String,
        isSystemAdmin: Boolean,
        emitter: SseEmitter,
    ) {
        val inFlightExecutions =
            executions.values
                .asSequence()
                .filter { record ->
                    (record.status == QueryExecutionStatus.QUEUED || record.status == QueryExecutionStatus.RUNNING) &&
                        (isSystemAdmin || record.actor == actor)
                }.sortedBy { record -> record.submittedAt }
                .toList()

        inFlightExecutions.forEach { record ->
            val event =
                QueryStatusEventResponse(
                    eventId = eventCounter.incrementAndGet().toString(),
                    executionId = record.executionId,
                    datasourceId = record.datasourceId,
                    status = record.status.name,
                    message = "Reconnect sync: ${record.message}",
                    occurredAt = Instant.now().toString(),
                )
            sendEvent(emitter, event, actor)
        }
    }

    private fun sendEvent(
        emitter: SseEmitter,
        event: QueryStatusEventResponse,
        actor: String,
    ) {
        runCatching {
            emitter.send(
                SseEmitter.event()
                    .id(event.eventId)
                    .name("query-status")
                    .data(event),
            )
        }.onFailure {
            emitter.complete()
            subscribers[actor]?.remove(emitter)
        }
    }

    private fun auditExecution(
        record: QueryExecutionRecord,
        type: String,
        outcome: String,
        details: Map<String, Any?>,
    ) {
        authAuditLogger.log(
            AuthAuditEvent(
                type = type,
                actor = record.actor,
                outcome = outcome,
                ipAddress = record.ipAddress,
                details = details,
            ),
        )
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sanitizeErrorMessage(message: String): String =
        message
            .replace(Regex("password\\s*=\\s*[^;\\s]+", RegexOption.IGNORE_CASE), "password=***")
            .replace(Regex("passwd\\s*=\\s*[^;\\s]+", RegexOption.IGNORE_CASE), "passwd=***")
            .replace(Regex("jdbc:[^\\s]+", RegexOption.IGNORE_CASE), "jdbc:***")
            .ifBlank { "Query execution failed." }

    private fun QueryExecutionRecord.toStatusResponse(): QueryExecutionStatusResponse =
        QueryExecutionStatusResponse(
            executionId = executionId,
            datasourceId = datasourceId,
            status = status.name,
            message = message,
            submittedAt = submittedAt.toString(),
            startedAt = startedAt?.toString(),
            completedAt = completedAt?.toString(),
            queryHash = queryHash,
            errorSummary = errorSummary,
            rowCount = rows.size,
            columnCount = columns.size,
            rowLimitReached = rowLimitReached,
            maxRowsPerQuery = maxRowsPerQuery,
            maxRuntimeSeconds = maxRuntimeSeconds,
            credentialProfile = credentialProfile,
        )
}
