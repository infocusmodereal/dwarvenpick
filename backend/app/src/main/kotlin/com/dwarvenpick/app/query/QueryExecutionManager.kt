package com.dwarvenpick.app.query

import com.dwarvenpick.app.auth.AuthAuditEvent
import com.dwarvenpick.app.auth.AuthAuditLogger
import com.dwarvenpick.app.datasource.DatasourcePoolManager
import com.dwarvenpick.app.datasource.ForbiddenNetworkTargetException
import com.dwarvenpick.app.datasource.QueryConnectionHandle
import com.dwarvenpick.app.datasource.UnresolvedNetworkTargetException
import com.dwarvenpick.app.datasource.shouldUseMysqlConnectorStreaming
import com.dwarvenpick.app.rbac.QueryAccessPolicy
import com.dwarvenpick.app.runtime.ApplicationInstanceId
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.InputStream
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement
import java.sql.Types
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

class QueryExportLimitExceededException(
    override val message: String,
) : RuntimeException(message)

class QueryReadOnlyViolationException(
    override val message: String,
) : RuntimeException(message)

class QueryJustificationRequiredException(
    override val message: String,
) : RuntimeException(message)

private class QueryCanceledException(
    override val message: String,
) : RuntimeException(message)

private class QueryRuntimeLimitException(
    override val message: String,
) : RuntimeException(message)

private const val RESULT_TRUNCATION_SUFFIX = "..."
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

private data class BufferedCellValue(
    val value: String?,
    val byteSize: Long,
    val truncated: Boolean,
)

private enum class BufferLimitReason {
    QUERY,
    INSTANCE,
}

private data class RemainingBufferCapacity(
    val bytes: Long,
    val message: String,
    val reason: BufferLimitReason,
)

private data class QueryExecutionRecord(
    val executionId: String,
    val actor: String,
    val ipAddress: String?,
    val datasourceId: String,
    val credentialProfile: String,
    val justification: String?,
    val defaultSchema: String?,
    val sql: String,
    val queryHash: String,
    val maxRowsPerQuery: Int,
    val maxBufferedBytes: Long,
    val maxCellBytes: Int,
    val maxRuntimeSeconds: Int,
    val concurrencyLimit: Int,
    val scriptStatementCount: Int,
    val scriptStopOnError: Boolean,
    val scriptTransactionMode: ScriptTransactionMode,
    val scriptStatements: MutableList<QueryScriptStatementSummary>,
    @Volatile var status: QueryExecutionStatus,
    @Volatile var message: String,
    @Volatile var errorSummary: String?,
    val submittedAt: Instant,
    @Volatile var startedAt: Instant?,
    @Volatile var completedAt: Instant?,
    @Volatile var rowLimitReached: Boolean,
    @Volatile var resultLimitMessage: String?,
    val resultBuffer: QueryResultPageBuffer,
    @Volatile var lastAccessedAt: Instant,
    @Volatile var resultsExpired: Boolean,
    @Volatile var cancelRequested: Boolean,
    @Volatile var activeStatement: Statement?,
    @Volatile var activeConnectionHandle: QueryConnectionHandle?,
    @Volatile var activeConnectionRequiresEviction: Boolean,
    @Volatile var activeConnection: Connection?,
    @Volatile var executionFuture: Future<*>?,
    val legacyCancelCheckGate: LegacyCancelCheckGate,
    val lifecycleLock: Any = Any(),
    val activeExportCount: AtomicInteger = AtomicInteger(0),
)

@Service
class QueryExecutionManager(
    private val datasourcePoolManager: DatasourcePoolManager,
    private val authAuditLogger: AuthAuditLogger,
    private val queryExecutionProperties: QueryExecutionProperties,
    private val queryJustificationPolicy: QueryJustificationPolicy,
    private val queryExecutionLimitPolicy: QueryExecutionLimitPolicy,
    private val queryHistoryRepository: QueryHistoryRepository,
    private val queryRuntimeRepository: QueryRuntimeRepository,
    private val queryAdmissionRepository: QueryAdmissionRepository,
    private val persistedQueryResultAccessService: PersistedQueryResultAccessService,
    private val applicationInstanceId: ApplicationInstanceId,
    private val queryLifecycleMetrics: QueryLifecycleMetrics,
    private val meterRegistry: MeterRegistry,
) {
    private val virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()
    private val executions = ConcurrentHashMap<String, QueryExecutionRecord>()
    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>()
    private val eventCounter = AtomicLong(0)
    private val instanceBufferedResultBytes = AtomicLong(0)
    private val logger = LoggerFactory.getLogger(QueryExecutionManager::class.java)

    @PostConstruct
    fun registerQueryMetrics() {
        meterRegistry.gauge("dwarvenpick.query.active", Tags.of("status", "queued"), this) { manager ->
            manager.countExecutions(QueryExecutionStatus.QUEUED).toDouble()
        }
        meterRegistry.gauge("dwarvenpick.query.active", Tags.of("status", "running"), this) { manager ->
            manager.countExecutions(QueryExecutionStatus.RUNNING).toDouble()
        }
        meterRegistry.gauge("dwarvenpick.query.buffered.bytes", this) { manager ->
            manager.instanceBufferedResultBytes.get().toDouble()
        }
        meterRegistry.gauge("dwarvenpick.query.buffered.budget.bytes", this) { manager ->
            manager.instanceBufferedResultBudgetBytes().toDouble()
        }
    }

    fun submitQuery(
        actor: String,
        ipAddress: String?,
        request: QueryExecutionRequest,
        policy: QueryAccessPolicy,
    ): QueryExecutionResponse {
        val normalizedSql = request.sql.trim()
        val statementSegments =
            SqlStatementSplitter
                .splitSqlStatements(normalizedSql)
                .filter { segment ->
                    SqlSafety
                        .stripLeadingSqlComments(segment.sql)
                        .trim()
                        .trimStart(';')
                        .isNotBlank()
                }

        if (statementSegments.isEmpty()) {
            throw IllegalArgumentException("SQL is empty.")
        }

        val hasWriteIntent = statementSegments.any { segment -> !SqlSafety.isReadOnlySql(segment.sql) }
        if (policy.readOnly && hasWriteIntent) {
            meterRegistry
                .counter(
                    "dwarvenpick.query.execute.attempts",
                    "outcome",
                    "blocked_read_only",
                    "datasourceId",
                    request.datasourceId.trim(),
                ).increment()
            throw QueryReadOnlyViolationException(
                "Read-only mode is enabled for this datasource access mapping. Only SELECT-like statements are allowed.",
            )
        }
        val justification = normalizeQueryJustification(request.justification)
        val defaultSchema = QueryDefaultSchema.normalize(request.defaultSchema)
        enforceWriteJustification(
            actor = actor,
            ipAddress = ipAddress,
            datasourceId = request.datasourceId.trim(),
            policy = policy,
            hasWriteIntent = hasWriteIntent,
            justification = justification,
        )
        val queryHash = sha256Hex(normalizedSql)
        val executionId = UUID.randomUUID().toString()

        val effectiveLimits = queryExecutionLimitPolicy.resolve(policy)
        val maxRows = effectiveLimits.maxRowsPerQuery
        val maxBufferedBytes = queryExecutionProperties.maxBufferedBytes.coerceAtLeast(1L)
        val maxCellBytes = queryExecutionProperties.maxCellBytes.coerceAtLeast(1)
        val maxRuntimeSeconds = effectiveLimits.maxRuntimeSeconds
        val concurrencyLimit = effectiveLimits.concurrencyLimit

        val record =
            QueryExecutionRecord(
                executionId = executionId,
                actor = actor,
                ipAddress = ipAddress,
                datasourceId = request.datasourceId.trim(),
                credentialProfile = policy.credentialProfile,
                justification = justification,
                defaultSchema = defaultSchema,
                sql = normalizedSql,
                queryHash = queryHash,
                maxRowsPerQuery = maxRows,
                maxBufferedBytes = maxBufferedBytes,
                maxCellBytes = maxCellBytes,
                maxRuntimeSeconds = maxRuntimeSeconds,
                concurrencyLimit = concurrencyLimit,
                scriptStatementCount = statementSegments.size,
                scriptStopOnError = request.stopOnError,
                scriptTransactionMode = request.transactionMode,
                scriptStatements = mutableListOf(),
                status = QueryExecutionStatus.QUEUED,
                message = "Query accepted and queued.",
                errorSummary = null,
                submittedAt = Instant.now(),
                startedAt = null,
                completedAt = null,
                rowLimitReached = false,
                resultLimitMessage = null,
                resultBuffer = QueryResultPageBuffer(queryExecutionProperties.resultChunkRows),
                lastAccessedAt = Instant.now(),
                resultsExpired = false,
                cancelRequested = false,
                activeStatement = null,
                activeConnectionHandle = null,
                activeConnectionRequiresEviction = false,
                activeConnection = null,
                executionFuture = null,
                legacyCancelCheckGate =
                    LegacyCancelCheckGate(queryExecutionProperties.remoteControlPollIntervalMs),
            )
        val admissionResult =
            queryAdmissionRepository.reserve(
                record = record.toPersistedRuntimeRecord(),
                concurrencyLimit = concurrencyLimit,
            )
        if (admissionResult == QueryAdmissionResult.LIMIT_REACHED) {
            meterRegistry
                .counter(
                    "dwarvenpick.query.execute.attempts",
                    "outcome",
                    "blocked_concurrency",
                    "datasourceId",
                    record.datasourceId,
                ).increment()
            throw QueryConcurrencyLimitException(
                "Concurrent query limit reached ($concurrencyLimit). Cancel an active query before running another.",
            )
        }

        executions[executionId] = record
        syncHistoryOnly(record)
        publishEvent(record, "Query queued.")
        logger.info(
            "query_execution queued executionId={} actor={} datasourceId={} queryHash={}",
            executionId,
            actor,
            record.datasourceId,
            queryHash,
        )
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
        meterRegistry
            .counter(
                "dwarvenpick.query.execute.attempts",
                "outcome",
                "queued",
                "datasourceId",
                record.datasourceId,
            ).increment()

        val future = virtualExecutor.submit { executeQueuedQuery(record) }
        record.executionFuture = future

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
        val record =
            executions[executionId]
                ?: return requestRemoteCancel(actor, isSystemAdmin, executionId)
        enforceExecutionAccess(actor, isSystemAdmin, record)
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
            meterRegistry
                .counter(
                    "dwarvenpick.query.cancel.total",
                    "datasourceId",
                    record.datasourceId,
                ).increment()
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
        closeActiveConnection(record)
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
        meterRegistry
            .counter(
                "dwarvenpick.query.cancel.total",
                "datasourceId",
                record.datasourceId,
            ).increment()

        return QueryCancelResponse(
            executionId = record.executionId,
            status = record.status.name,
            message = record.message,
            canceledAt = Instant.now().toString(),
        )
    }

    fun killQuery(
        actor: String,
        isSystemAdmin: Boolean,
        executionId: String,
    ): QueryKillResponse {
        if (!isSystemAdmin) {
            throw QueryExecutionForbiddenException("Killing queries requires the SYSTEM_ADMIN role.")
        }

        val record =
            executions[executionId]
                ?: return requestRemoteKill(actor, executionId)
        enforceExecutionAccess(actor, isSystemAdmin, record)
        if (record.status in terminalStatuses()) {
            return QueryKillResponse(
                executionId = record.executionId,
                status = record.status.name,
                message = "Query already finished with status ${record.status.name}.",
                killedAt = Instant.now().toString(),
            )
        }

        record.cancelRequested = true
        if (record.status == QueryExecutionStatus.QUEUED) {
            record.executionFuture?.cancel(true)
            markCanceled(record, "Query killed before execution started.")
            auditExecution(
                record = record,
                type = "query.kill",
                outcome = "killed",
                details = mapOf("executionId" to record.executionId),
            )
            meterRegistry
                .counter(
                    "dwarvenpick.query.kill.total",
                    "datasourceId",
                    record.datasourceId,
                ).increment()
            return QueryKillResponse(
                executionId = record.executionId,
                status = record.status.name,
                message = record.message,
                killedAt = Instant.now().toString(),
            )
        }

        runCatching { record.activeStatement?.cancel() }
        closeActiveConnection(record)
        record.executionFuture?.cancel(true)

        if (record.status == QueryExecutionStatus.RUNNING) {
            markCanceled(record, "Query killed by admin request.")
        }

        auditExecution(
            record = record,
            type = "query.kill",
            outcome = "killed",
            details = mapOf("executionId" to record.executionId),
        )
        meterRegistry
            .counter(
                "dwarvenpick.query.kill.total",
                "datasourceId",
                record.datasourceId,
            ).increment()

        return QueryKillResponse(
            executionId = record.executionId,
            status = record.status.name,
            message = record.message,
            killedAt = Instant.now().toString(),
        )
    }

    fun listActiveExecutions(
        actor: String,
        isSystemAdmin: Boolean,
        datasourceId: String?,
        actorFilter: String? = null,
    ): List<ActiveQueryExecutionResponse> {
        val normalizedDatasourceId = datasourceId?.trim()?.takeIf { value -> value.isNotBlank() }
        val normalizedActorFilter = actorFilter?.trim()?.takeIf { value -> value.isNotBlank() }
        val now = Instant.now()

        return queryRuntimeRepository
            .listActiveMetadata(
                actor = actor,
                isSystemAdmin = isSystemAdmin,
                datasourceId = normalizedDatasourceId,
                actorFilter = normalizedActorFilter,
            ).asSequence()
            .map { record ->
                ActiveQueryExecutionResponse(
                    executionId = record.executionId,
                    actor = record.actor,
                    datasourceId = record.datasourceId,
                    credentialProfile = record.credentialProfile,
                    justification = record.justification,
                    status = record.status.name,
                    message = record.message,
                    queryHash = record.queryHash,
                    sqlPreview = record.sql?.let { buildSqlPreview(it) } ?: "(query text redacted)",
                    submittedAt = record.submittedAt.toString(),
                    startedAt = record.startedAt?.toString(),
                    durationMs =
                        record.startedAt?.let { startedAt ->
                            Duration.between(startedAt, now).toMillis().coerceAtLeast(0)
                        },
                    cancelRequested = record.cancelRequested,
                )
            }.toList()
    }

    fun getExecutionStatus(
        actor: String,
        isSystemAdmin: Boolean,
        executionId: String,
    ): QueryExecutionStatusResponse {
        val record = executions[executionId]
        if (record != null) {
            enforceExecutionAccess(actor, isSystemAdmin, record)
            return record.toStatusResponse()
        }
        val persisted =
            queryRuntimeRepository.findMetadata(executionId)
                ?: throw QueryExecutionNotFoundException("Query execution '$executionId' was not found.")
        enforceExecutionAccess(actor, isSystemAdmin, persisted)
        return persisted.toStatusResponse()
    }

    fun getQueryResults(
        actor: String,
        isSystemAdmin: Boolean,
        executionId: String,
        request: QueryResultsRequest,
    ): QueryResultsResponse {
        val record = executions[executionId]
        if (record == null) {
            val persisted =
                queryRuntimeRepository.findMetadata(executionId)
                    ?: throw QueryExecutionNotFoundException("Query execution '$executionId' was not found.")
            enforceExecutionAccess(actor, isSystemAdmin, persisted)
            return persistedQueryResultAccessService.getResults(persisted, request)
        }
        enforceExecutionAccess(actor, isSystemAdmin, record)
        val persisted =
            queryRuntimeRepository.findMetadata(executionId)
                ?: throw QueryExecutionNotFoundException("Query execution '$executionId' was not found.")
        return persistedQueryResultAccessService.getResults(persisted, request)
    }

    fun prepareCsvExport(
        actor: String,
        isSystemAdmin: Boolean,
        executionId: String,
        includeHeaders: Boolean,
        maxExportRows: Int,
    ): QueryCsvExportPayload {
        val record = executions[executionId]
        if (record == null) {
            val persisted =
                queryRuntimeRepository.findMetadata(executionId)
                    ?: throw QueryExecutionNotFoundException("Query execution '$executionId' was not found.")
            enforceExecutionAccess(actor, isSystemAdmin, persisted)
            return persistedQueryResultAccessService.prepareCsvExport(persisted, includeHeaders, maxExportRows)
        }
        enforceExecutionAccess(actor, isSystemAdmin, record)
        val persisted =
            queryRuntimeRepository.findMetadata(executionId)
                ?: throw QueryExecutionNotFoundException("Query execution '$executionId' was not found.")
        return persistedQueryResultAccessService.prepareCsvExport(persisted, includeHeaders, maxExportRows).also {
            record.activeExportCount.incrementAndGet()
        }
    }

    fun completeCsvExport(executionId: String) {
        executions[executionId]
            ?.activeExportCount
            ?.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    fun listHistory(
        actor: String,
        isSystemAdmin: Boolean,
        datasourceId: String?,
        status: QueryExecutionStatus?,
        from: Instant?,
        to: Instant?,
        limit: Int,
        actorFilter: String?,
        offset: Int = 0,
        sortOrder: QueryHistorySortOrder = QueryHistorySortOrder.NEWEST,
    ): List<QueryHistoryEntryResponse> {
        val filter =
            QueryHistoryFilter(
                actor = actor,
                isSystemAdmin = isSystemAdmin,
                datasourceId = datasourceId,
                status = status,
                from = from,
                to = to,
                limit = limit,
                offset = offset,
                actorFilter = actorFilter,
                sortOrder = sortOrder,
            )
        return queryHistoryRepository.list(filter).map { historyRecord -> historyRecord.toResponse() }
    }

    fun pruneHistoryOlderThan(cutoff: Instant): Int = queryHistoryRepository.pruneOlderThan(cutoff)

    fun redactHistoryQueryTextOlderThan(cutoff: Instant): Int = queryHistoryRepository.redactQueryTextOlderThan(cutoff)

    fun pruneRuntimeOlderThan(cutoff: Instant): Int = queryRuntimeRepository.pruneOlderThan(cutoff)

    fun redactRuntimeQueryTextOlderThan(cutoff: Instant): Int = queryRuntimeRepository.redactQueryTextOlderThan(cutoff)

    fun subscribeToStatusEvents(
        actor: String,
        isSystemAdmin: Boolean,
    ): SseEmitter {
        val emitter = SseEmitter(0L)
        val actorSubscribers = subscribers.computeIfAbsent(actor) { CopyOnWriteArrayList() }
        actorSubscribers.add(emitter)

        emitter.onCompletion { actorSubscribers.remove(emitter) }
        emitter.onTimeout {
            actorSubscribers.remove(emitter)
            runCatching { emitter.complete() }
        }
        emitter.onError { _ ->
            actorSubscribers.remove(emitter)
            runCatching { emitter.complete() }
        }

        sendInFlightSnapshot(actor, isSystemAdmin, emitter)
        return emitter
    }

    @Scheduled(fixedDelayString = "\${dwarvenpick.query.sse-heartbeat-interval-ms:30000}")
    fun sendSseHeartbeat() {
        subscribers.entries.forEach { (actor, emitters) ->
            if (emitters.isEmpty()) {
                return@forEach
            }
            emitters.toList().forEach { emitter ->
                sendHeartbeat(emitter, actor)
            }
        }
    }

    @Scheduled(
        fixedDelayString = "\${dwarvenpick.query.remote-control-poll-interval-ms:1000}",
        scheduler = "queryLifecycleTaskScheduler",
    )
    fun pollRemoteControlRequests() {
        runCatching {
            queryRuntimeRepository
                .listPendingRemoteControlExecutionIds(applicationInstanceId.value)
                .forEach { executionId ->
                    val record = executions[executionId] ?: return@forEach
                    if (record.status in terminalStatuses()) {
                        return@forEach
                    }
                    val request = claimRemoteControlRequest(record) ?: return@forEach
                    virtualExecutor.submit { applyRemoteControl(record, request) }
                }
        }.onFailure { exception ->
            logger.warn("query_remote_control poll failed; pending requests remain durable", exception)
        }
    }

    @Scheduled(fixedDelayString = "\${dwarvenpick.query.cleanup-interval-ms:30000}")
    fun cleanupExpiredSessions() {
        val now = Instant.now()
        val resultTtlSeconds = queryExecutionProperties.resultSessionTtlSeconds.coerceAtLeast(60)
        val retentionSeconds = queryExecutionProperties.executionRetentionSeconds.coerceAtLeast(120)
        val staleCutoff = now.minusSeconds(queryExecutionProperties.activeExecutionStaleSeconds.coerceAtLeast(60))

        val removableExecutionIds = mutableListOf<String>()
        executions.forEach { (executionId, record) ->
            if (record.status !in terminalStatuses()) {
                queryRuntimeRepository.updateHeartbeat(
                    executionId = executionId,
                    ownerInstanceId = applicationInstanceId.value,
                    heartbeatAt = now,
                )
            }
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

            if (record.activeExportCount.get() > 0) {
                record.lastAccessedAt = now
                queryRuntimeRepository.touchResultAccess(record.executionId, now)
                return@forEach
            }

            if (!record.resultsExpired) {
                val expired =
                    queryRuntimeRepository.expireResultsIfIdle(
                        executionId = record.executionId,
                        cutoff = now.minusSeconds(resultTtlSeconds),
                        message = "Result session expired. Re-run the query.",
                    )
                if (expired) {
                    clearBufferedResults(record)
                    record.resultsExpired = true
                    record.message = "Result session expired. Re-run the query."
                    syncHistoryOnly(record)
                }
            }

            val completedAt = record.completedAt ?: record.submittedAt
            if (Duration.between(completedAt, now).seconds > retentionSeconds) {
                removableExecutionIds.add(executionId)
            }
        }

        removableExecutionIds.forEach { executionId ->
            removeExecution(executionId)
        }
        queryRuntimeRepository.markStaleActiveExecutions(
            staleCutoff,
            "Query state was lost after its backend stopped heartbeating.",
        )
        queryRuntimeRepository.pruneOlderThan(now.minusSeconds(retentionSeconds))
    }

    @PreDestroy
    fun shutdownGracefully() {
        val gracePeriodSeconds = queryExecutionProperties.shutdownGracePeriodSeconds.coerceAtLeast(1)
        val inFlightExecutions =
            executions.values.filter { record ->
                record.status == QueryExecutionStatus.QUEUED || record.status == QueryExecutionStatus.RUNNING
            }

        if (inFlightExecutions.isEmpty()) {
            virtualExecutor.shutdownNow()
            return
        }

        logger.info(
            "query_execution shutdown initiated activeQueries={} gracePeriodSeconds={}",
            inFlightExecutions.size,
            gracePeriodSeconds,
        )
        inFlightExecutions.forEach { record ->
            record.cancelRequested = true
            runCatching { record.activeStatement?.cancel() }
            closeActiveConnection(record)
            record.executionFuture?.cancel(true)
        }

        val shutdownDeadline = Instant.now().plusSeconds(gracePeriodSeconds.toLong())
        while (Instant.now().isBefore(shutdownDeadline)) {
            val remaining =
                executions.values.count { record ->
                    record.status == QueryExecutionStatus.QUEUED || record.status == QueryExecutionStatus.RUNNING
                }
            if (remaining == 0) {
                break
            }
            Thread.sleep(100)
        }

        executions.values.forEach { record ->
            if (record.status == QueryExecutionStatus.QUEUED || record.status == QueryExecutionStatus.RUNNING) {
                markCanceled(record, "Query canceled due to service shutdown.")
                closeRuntimeResources(record)
            }
        }

        virtualExecutor.shutdownNow()
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
            runCatching { claimRemoteControlRequest(record) }
            throwIfCanceled(record)
            markSucceeded(record)
            auditExecution(
                record = record,
                type = "query.execute",
                outcome = "succeeded",
                details =
                    mapOf(
                        "executionId" to record.executionId,
                        "rows" to record.resultBuffer.totalRowCount(),
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
            meterRegistry
                .counter(
                    "dwarvenpick.query.timeout.total",
                    "datasourceId",
                    record.datasourceId,
                ).increment()
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
        } catch (ex: ForbiddenNetworkTargetException) {
            markFailed(record, sanitizeErrorMessage(ex.message ?: "Datasource host is blocked by network guard policy."))
            meterRegistry
                .counter(
                    "dwarvenpick.query.network_guard.blocked.total",
                    "datasourceId",
                    record.datasourceId,
                ).increment()
            auditExecution(
                record = record,
                type = "query.execute",
                outcome = "failed",
                details =
                    mapOf(
                        "executionId" to record.executionId,
                        "reason" to "network_blocked",
                    ),
            )
        } catch (ex: UnresolvedNetworkTargetException) {
            markFailed(record, sanitizeErrorMessage(ex.message ?: "Datasource host could not be resolved for network guard validation."))
            meterRegistry
                .counter(
                    "dwarvenpick.query.network_guard.unresolved.total",
                    "datasourceId",
                    record.datasourceId,
                ).increment()
            auditExecution(
                record = record,
                type = "query.execute",
                outcome = "failed",
                details =
                    mapOf(
                        "executionId" to record.executionId,
                        "reason" to "network_unresolved",
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
        executeSqlAgainstDatasource(record)
    }

    private fun executeSqlAgainstDatasource(record: QueryExecutionRecord) {
        val handle =
            datasourcePoolManager.openConnection(
                datasourceId = record.datasourceId,
                credentialProfile = record.credentialProfile,
            )
        record.activeConnectionHandle = handle
        record.activeConnection = handle.connection

        val defaultSchemaScope =
            QueryDefaultSchema.apply(
                connection = handle.connection,
                engine = handle.spec.engine,
                defaultSchema = record.defaultSchema,
            )
        record.activeConnectionRequiresEviction = defaultSchemaScope.evictConnectionOnClose

        try {
            val statement =
                createQueryStatement(handle).apply {
                    queryTimeout = record.maxRuntimeSeconds
                    runCatching {
                        maxFieldSize = jdbcFieldSizeLimit(record.maxCellBytes)
                    }.onFailure { exception ->
                        logger.debug("JDBC driver rejected query max field size.", exception)
                    }
                    runCatching {
                        fetchSize = jdbcFetchSizeFor(handle)
                    }.onFailure { exception ->
                        logger.debug("JDBC driver rejected query fetch size.", exception)
                    }
                }
            record.activeStatement = statement
            val statementSegments =
                SqlStatementSplitter
                    .splitSqlStatements(record.sql)
                    .filter { segment ->
                        SqlSafety
                            .stripLeadingSqlComments(segment.sql)
                            .trim()
                            .trimStart(';')
                            .isNotBlank()
                    }

            if (statementSegments.isEmpty()) {
                throw IllegalArgumentException("SQL is empty.")
            }

            record.scriptStatements.clear()
            val isScript = statementSegments.size > 1
            val cancelOnEarlyResultLimit = shouldStreamMysqlResults(handle)

            if (!isScript) {
                executeStatementAndBuffer(record, statement, statementSegments[0].sql, cancelOnEarlyResultLimit)
                return
            }

            executeScript(record, handle.connection, statement, statementSegments, cancelOnEarlyResultLimit)
        } finally {
            defaultSchemaScope.close()
            if (defaultSchemaScope.evictConnectionOnClose) {
                record.activeConnectionRequiresEviction = true
            }
        }
    }

    private fun executeScript(
        record: QueryExecutionRecord,
        connection: Connection,
        statement: Statement,
        segments: List<SqlStatementSegment>,
        cancelOnEarlyResultLimit: Boolean,
    ) {
        val failures = mutableListOf<String>()
        val originalAutoCommit = connection.autoCommit
        val transactionMode = record.scriptTransactionMode

        if (transactionMode == ScriptTransactionMode.TRANSACTION) {
            connection.autoCommit = false
        }

        try {
            segments.forEachIndexed { index, segment ->
                throwIfCanceled(record)
                enforceRuntimeLimit(record)

                val statementNumber = index + 1
                record.message = "Running statement $statementNumber/${segments.size}..."
                publishEvent(record, record.message)

                val sqlPreview = buildSqlPreview(segment.sql)

                try {
                    val bufferResults = index == segments.lastIndex
                    executeStatement(record, statement, segment.sql, bufferResults, cancelOnEarlyResultLimit)
                    record.scriptStatements.add(
                        QueryScriptStatementSummary(
                            index = statementNumber,
                            status = "SUCCEEDED",
                            sqlPreview = sqlPreview,
                            message = "Succeeded.",
                        ),
                    )
                } catch (ex: Throwable) {
                    val message = sanitizeErrorMessage(ex.message ?: "Statement failed.")
                    failures.add("Statement $statementNumber: $message")
                    record.scriptStatements.add(
                        QueryScriptStatementSummary(
                            index = statementNumber,
                            status = "FAILED",
                            sqlPreview = sqlPreview,
                            message = message,
                        ),
                    )

                    if (transactionMode == ScriptTransactionMode.TRANSACTION) {
                        throw ex
                    }
                    if (record.scriptStopOnError) {
                        throw ex
                    }
                }
            }

            if (transactionMode == ScriptTransactionMode.TRANSACTION) {
                connection.commit()
            }
        } catch (ex: Throwable) {
            if (transactionMode == ScriptTransactionMode.TRANSACTION) {
                runCatching { connection.rollback() }
            }
            throw ex
        } finally {
            if (transactionMode == ScriptTransactionMode.TRANSACTION) {
                runCatching { connection.autoCommit = originalAutoCommit }
            }
        }

        if (failures.isNotEmpty()) {
            throw RuntimeException(
                "Script completed with ${failures.size} error(s). ${failures.first()}",
            )
        }
    }

    private fun executeStatementAndBuffer(
        record: QueryExecutionRecord,
        statement: Statement,
        sql: String,
        cancelOnEarlyResultLimit: Boolean,
    ) {
        executeStatement(record, statement, sql, bufferResults = true, cancelOnEarlyResultLimit)
    }

    private fun executeStatement(
        record: QueryExecutionRecord,
        statement: Statement,
        sql: String,
        bufferResults: Boolean,
        cancelOnEarlyResultLimit: Boolean,
    ) {
        if (!bufferResults) {
            val hasResultSet = statement.execute(sql)
            if (hasResultSet) {
                runCatching { statement.resultSet?.close() }
            }
            return
        }

        record.rowLimitReached = false
        record.resultLimitMessage = null
        clearBufferedResults(record)

        val hasResultSet = statement.execute(sql)
        if (!hasResultSet) {
            bufferUpdateCount(record, statement.updateCount)
            return
        }

        val resultSet = statement.resultSet
        try {
            val stoppedEarly = !bufferResultSet(record, resultSet)
            if (stoppedEarly) {
                if (cancelOnEarlyResultLimit) {
                    cancelActiveStatementAfterResultLimit(record)
                }
                runCatching { resultSet.close() }
                    .onFailure { exception ->
                        logger.debug("JDBC driver rejected streaming result close after result limit.", exception)
                    }
                return
            }
            resultSet.close()
        } catch (ex: Throwable) {
            if (cancelOnEarlyResultLimit) {
                cancelActiveStatementAfterResultLimit(record)
            }
            runCatching { resultSet.close() }
                .onFailure { closeException -> ex.addSuppressed(closeException) }
            throw ex
        }
    }

    private fun bufferUpdateCount(
        record: QueryExecutionRecord,
        affectedRows: Int,
    ) {
        replaceBufferedColumns(record, listOf(QueryResultColumn(name = "affected_rows", jdbcType = "INTEGER")))
        appendBufferedRow(record, listOf(affectedRows.toString()))
    }

    private fun bufferResultSet(
        record: QueryExecutionRecord,
        resultSet: ResultSet,
    ): Boolean {
        val metadata = resultSet.metaData
        val resolvedColumns =
            (1..metadata.columnCount).map { index ->
                QueryResultColumn(
                    name = metadata.getColumnLabel(index) ?: "col_$index",
                    jdbcType = metadata.getColumnTypeName(index) ?: "UNKNOWN",
                )
            }
        replaceBufferedColumns(record, resolvedColumns)

        while (resultSet.next()) {
            throwIfCanceled(record)
            enforceRuntimeLimit(record)

            val row = readBufferedRow(record, resultSet, metadata) ?: return false
            if (!appendBufferedCells(record, row)) {
                return false
            }
        }

        return true
    }

    private fun readBufferedRow(
        record: QueryExecutionRecord,
        resultSet: ResultSet,
        metadata: ResultSetMetaData,
    ): List<BufferedCellValue>? {
        val cells = mutableListOf<BufferedCellValue>()
        var rowBytes = 0L

        for (index in 1..metadata.columnCount) {
            val separatorBytes = if (cells.isEmpty()) 0L else 1L
            val capacity = remainingBufferCapacity(record, rowBytes + separatorBytes)
            if (capacity.bytes <= 0) {
                markBufferLimit(record, capacity)
                return null
            }

            val cellLimit =
                minOf(record.maxCellBytes.toLong(), capacity.bytes)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            val cell = readBufferedCellValue(resultSet, metadata, index, cellLimit)
            if (cell.truncated && cellLimit < record.maxCellBytes) {
                markBufferLimit(record, capacity)
                return null
            }

            cells.add(cell)
            rowBytes += separatorBytes + cell.byteSize
        }

        return cells
    }

    private fun appendBufferedRow(
        record: QueryExecutionRecord,
        values: List<Any?>,
    ): Boolean =
        appendBufferedCells(
            record,
            values.map { value -> toBufferedCellValue(value, record.maxCellBytes) },
        )

    private fun appendBufferedCells(
        record: QueryExecutionRecord,
        cells: List<BufferedCellValue>,
    ): Boolean {
        val rowSeparatorBytes = (cells.size - 1).coerceAtLeast(0).toLong()
        val rowBytes = cells.sumOf { cell -> cell.byteSize } + rowSeparatorBytes

        if (record.resultBuffer.totalRowCount() >= record.maxRowsPerQuery) {
            markResultLimit(record, "Query succeeded. Result truncated at ${record.maxRowsPerQuery} rows.")
            return false
        }

        if (record.resultBuffer.totalLogicalByteCount() + rowBytes > record.maxBufferedBytes) {
            markResultLimit(record, resultBufferLimitMessage(record.maxBufferedBytes))
            return false
        }

        val instanceBudgetBytes = instanceBufferedResultBudgetBytes()
        if (!tryReserveInstanceBufferedBytes(rowBytes, instanceBudgetBytes)) {
            markResultLimit(record, instanceBufferLimitMessage(instanceBudgetBytes))
            meterRegistry
                .counter(
                    "dwarvenpick.query.result_buffer.rejections",
                    "reason",
                    "instance_budget",
                ).increment()
            return false
        }

        val page = record.resultBuffer.append(cells.map { cell -> cell.value }, rowBytes)
        if (page != null) {
            persistIntermediatePage(record, page)
        }

        if (cells.any { cell -> cell.truncated }) {
            markResultLimit(
                record,
                "Query succeeded. One or more cell values were truncated at " +
                    "the ${formatBytes(record.maxCellBytes.toLong())} cell size limit.",
                overwrite = false,
            )
        }

        return true
    }

    private fun readBufferedCellValue(
        resultSet: ResultSet,
        metadata: ResultSetMetaData,
        index: Int,
        maxCellBytes: Int,
    ): BufferedCellValue {
        val jdbcType = runCatching { metadata.getColumnType(index) }.getOrDefault(Types.OTHER)
        val jdbcTypeName =
            runCatching { metadata.getColumnTypeName(index) }
                .getOrNull()
                ?.lowercase(Locale.ROOT)
                .orEmpty()

        if (isCharacterLikeColumn(jdbcType, jdbcTypeName)) {
            val reader =
                runCatching {
                    if (
                        jdbcType == Types.NCLOB ||
                        jdbcType == Types.NCHAR ||
                        jdbcType == Types.NVARCHAR ||
                        jdbcType == Types.LONGNVARCHAR
                    ) {
                        resultSet.getNCharacterStream(index)
                    } else {
                        resultSet.getCharacterStream(index)
                    }
                }.getOrNull()
            if (reader != null) {
                return reader.use { toBufferedCellValue(it, maxCellBytes) }
            }
            if (resultSet.wasNull()) {
                return nullBufferedCellValue()
            }
        }

        if (isBinaryLikeColumn(jdbcType)) {
            val stream = runCatching { resultSet.getBinaryStream(index) }.getOrNull()
            if (stream != null) {
                return stream.use { toBufferedBinaryCellValue(it, maxCellBytes) }
            }
            if (resultSet.wasNull()) {
                return nullBufferedCellValue()
            }
        }

        return toBufferedCellValue(resultSet.getObject(index), maxCellBytes)
    }

    private fun toBufferedCellValue(
        value: Any?,
        maxCellBytes: Int,
    ): BufferedCellValue =
        when (value) {
            null -> nullBufferedCellValue()
            is ByteArray -> value.inputStream().use { stream -> toBufferedBinaryCellValue(stream, maxCellBytes) }
            is java.sql.Clob -> value.characterStream.use { reader -> toBufferedCellValue(reader, maxCellBytes) }
            is java.sql.NClob -> value.characterStream.use { reader -> toBufferedCellValue(reader, maxCellBytes) }
            is java.sql.SQLXML -> value.characterStream.use { reader -> toBufferedCellValue(reader, maxCellBytes) }
            else -> toBufferedTextCellValue(value.toString(), maxCellBytes)
        }

    private fun toBufferedCellValue(
        reader: Reader,
        maxCellBytes: Int,
    ): BufferedCellValue {
        val bufferedReader = reader.buffered()
        val suffix = RESULT_TRUNCATION_SUFFIX.take(maxCellBytes)
        val suffixBytes = suffix.toByteArray(StandardCharsets.UTF_8).size
        val valueBudget = (maxCellBytes - suffixBytes).coerceAtLeast(0)
        val builder = StringBuilder()
        var usedBytes = 0
        var truncated = false

        while (true) {
            val next = bufferedReader.read()
            if (next == -1) {
                break
            }

            val character = next.toChar()
            val characterBytes = character.toString().toByteArray(StandardCharsets.UTF_8).size
            if (usedBytes + characterBytes > valueBudget) {
                truncated = true
                break
            }

            builder.append(character)
            usedBytes += characterBytes
        }

        val result =
            if (truncated) {
                builder.append(suffix).toString()
            } else {
                builder.toString()
            }

        return BufferedCellValue(
            value = result,
            byteSize = result.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            truncated = truncated,
        )
    }

    private fun toBufferedTextCellValue(
        text: String,
        maxCellBytes: Int,
    ): BufferedCellValue = text.reader().use { reader -> toBufferedCellValue(reader, maxCellBytes) }

    private fun toBufferedBinaryCellValue(
        inputStream: InputStream,
        maxCellBytes: Int,
    ): BufferedCellValue {
        val suffix = RESULT_TRUNCATION_SUFFIX.take(maxCellBytes)
        val suffixBytes = suffix.toByteArray(StandardCharsets.UTF_8).size
        val valueBudget = (maxCellBytes - suffixBytes).coerceAtLeast(0)
        val visibleByteBudget = valueBudget / 2
        val bytes =
            if (visibleByteBudget > 0) {
                inputStream.readNBytes(visibleByteBudget + 1)
            } else {
                inputStream.readNBytes(1)
            }
        val truncated = bytes.size > visibleByteBudget
        val visibleLength = bytes.size.coerceAtMost(visibleByteBudget)
        val builderCapacity =
            ((visibleLength.toLong() * 2L) + if (truncated) suffix.length.toLong() else 0L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        val builder = StringBuilder(builderCapacity)
        for (index in 0 until visibleLength) {
            val value = bytes[index].toInt() and 0xff
            builder.append(HEX_DIGITS[value ushr 4])
            builder.append(HEX_DIGITS[value and 0x0f])
        }
        if (truncated) {
            builder.append(suffix)
        }
        val result = builder.toString()

        return BufferedCellValue(
            value = result,
            byteSize = result.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            truncated = truncated,
        )
    }

    private fun nullBufferedCellValue(): BufferedCellValue = BufferedCellValue(value = null, byteSize = 0, truncated = false)

    private fun isCharacterLikeColumn(
        jdbcType: Int,
        jdbcTypeName: String,
    ): Boolean =
        jdbcType in
            setOf(
                Types.CHAR,
                Types.VARCHAR,
                Types.LONGVARCHAR,
                Types.NCHAR,
                Types.NVARCHAR,
                Types.LONGNVARCHAR,
                Types.CLOB,
                Types.NCLOB,
                Types.SQLXML,
            ) ||
            jdbcTypeName.contains("text") ||
            jdbcTypeName.contains("json") ||
            jdbcTypeName.contains("xml")

    private fun isBinaryLikeColumn(jdbcType: Int): Boolean =
        jdbcType in setOf(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB)

    private fun jdbcFieldSizeLimit(maxCellBytes: Int): Int =
        (maxCellBytes.toLong() + MAX_FIELD_SIZE_DETECTION_BYTES)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private fun clearBufferedResults(record: QueryExecutionRecord) {
        releaseInstanceBufferedBytes(record.resultBuffer.reset().pendingByteCount)
    }

    private fun persistIntermediatePage(
        record: QueryExecutionRecord,
        page: QueryResultPageSnapshot,
    ) {
        try {
            queryRuntimeRepository.appendResultPage(
                executionId = record.executionId,
                ownerInstanceId = applicationInstanceId.value,
                page = page,
            )
        } catch (_: QueryRuntimeWriteRejectedException) {
            record.cancelRequested = true
            clearBufferedResults(record)
            throw QueryCanceledException("Query execution ownership was canceled.")
        }
        releaseInstanceBufferedBytes(record.resultBuffer.acknowledge(page))
    }

    private fun removeExecution(executionId: String) {
        val removed = executions.remove(executionId) ?: return
        clearBufferedResults(removed)
        closeRuntimeResources(removed)
    }

    private fun replaceBufferedColumns(
        record: QueryExecutionRecord,
        columns: List<QueryResultColumn>,
    ) {
        record.resultBuffer.replaceColumns(columns)
    }

    private fun instanceBufferedResultBudgetBytes(): Long = queryExecutionProperties.maxBufferedBytesPerInstance.coerceAtLeast(0)

    private fun createQueryStatement(handle: QueryConnectionHandle): Statement =
        handle.connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)

    private fun jdbcFetchSizeFor(handle: QueryConnectionHandle): Int =
        if (shouldStreamMysqlResults(handle)) {
            Int.MIN_VALUE
        } else {
            queryExecutionProperties.jdbcFetchSize.coerceAtLeast(0)
        }

    private fun shouldStreamMysqlResults(handle: QueryConnectionHandle): Boolean =
        shouldUseMysqlConnectorStreaming(handle.spec.engine, handle.spec.driverClass)

    private fun remainingBufferCapacity(
        record: QueryExecutionRecord,
        pendingRowBytes: Long,
    ): RemainingBufferCapacity {
        val queryRemaining: Long
        val instanceRemaining: Long
        queryRemaining = record.maxBufferedBytes - record.resultBuffer.totalLogicalByteCount() - pendingRowBytes
        val instanceBudgetBytes = instanceBufferedResultBudgetBytes()
        instanceRemaining =
            if (instanceBudgetBytes > 0) {
                instanceBudgetBytes - instanceBufferedResultBytes.get() - pendingRowBytes
            } else {
                Long.MAX_VALUE
            }

        return if (queryRemaining <= instanceRemaining) {
            RemainingBufferCapacity(
                bytes = queryRemaining,
                message = resultBufferLimitMessage(record.maxBufferedBytes),
                reason = BufferLimitReason.QUERY,
            )
        } else {
            val instanceBudgetBytes = instanceBufferedResultBudgetBytes()
            RemainingBufferCapacity(
                bytes = instanceRemaining,
                message = instanceBufferLimitMessage(instanceBudgetBytes),
                reason = BufferLimitReason.INSTANCE,
            )
        }
    }

    private fun markBufferLimit(
        record: QueryExecutionRecord,
        capacity: RemainingBufferCapacity,
    ) {
        markResultLimit(record, capacity.message)
        if (capacity.reason == BufferLimitReason.INSTANCE) {
            meterRegistry
                .counter(
                    "dwarvenpick.query.result_buffer.rejections",
                    "reason",
                    "instance_budget",
                ).increment()
        }
    }

    private fun cancelActiveStatementAfterResultLimit(record: QueryExecutionRecord) {
        runCatching { record.activeStatement?.cancel() }
            .onFailure { exception ->
                logger.debug("JDBC driver rejected statement cancel after result limit.", exception)
            }
    }

    private fun tryReserveInstanceBufferedBytes(
        rowBytes: Long,
        instanceBudgetBytes: Long,
    ): Boolean {
        if (rowBytes <= 0) {
            return true
        }

        while (true) {
            val currentBytes = instanceBufferedResultBytes.get()
            val nextBytes = currentBytes + rowBytes
            if (instanceBudgetBytes > 0 && nextBytes > instanceBudgetBytes) {
                return false
            }
            if (instanceBufferedResultBytes.compareAndSet(currentBytes, nextBytes)) {
                return true
            }
        }
    }

    private fun releaseInstanceBufferedBytes(bytes: Long) {
        if (bytes <= 0) {
            return
        }
        instanceBufferedResultBytes.updateAndGet { current -> (current - bytes).coerceAtLeast(0) }
    }

    private fun markResultLimit(
        record: QueryExecutionRecord,
        message: String,
        overwrite: Boolean = true,
    ) {
        record.rowLimitReached = true
        if (overwrite || record.resultLimitMessage == null) {
            record.resultLimitMessage = message
        }
    }

    private fun resultBufferLimitMessage(maxBufferedBytes: Long): String =
        "Query succeeded. Result truncated at the ${formatBytes(maxBufferedBytes)} result buffer size limit."

    private fun instanceBufferLimitMessage(maxBufferedBytesPerInstance: Long): String =
        "Query succeeded. Result truncated because this backend reached the " +
            "${formatBytes(maxBufferedBytesPerInstance)} shared result buffer budget."

    private fun formatBytes(bytes: Long): String =
        when {
            bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)}MiB"
            bytes >= 1024L -> "${bytes / 1024L}KiB"
            else -> "${bytes}B"
        }

    private fun buildSqlPreview(sql: String): String {
        val normalized = SqlSafety.stripLeadingSqlComments(sql).trim()
        val singleLine =
            normalized
                .lineSequence()
                .firstOrNull()
                ?.trim()
                .orEmpty()
        if (singleLine.isBlank()) {
            return "(empty statement)"
        }
        val clipped = if (singleLine.length > 120) singleLine.take(117) + "..." else singleLine
        return clipped
    }

    private fun executeSqlInSimulation(record: QueryExecutionRecord) {
        val sql = record.sql.trim()
        val normalizedSql = sql.lowercase(Locale.ROOT)

        val sleepMatch = Regex("pg_sleep\\((\\d+)\\)").find(normalizedSql)
        if (sleepMatch != null) {
            val sleepSeconds = sleepMatch.groupValues[1].toLongOrNull()?.coerceAtLeast(0) ?: 0L
            val endAt = Instant.now().plusSeconds(sleepSeconds)
            replaceBufferedColumns(record, listOf(QueryResultColumn(name = "pg_sleep", jdbcType = "DOUBLE")))
            while (Instant.now().isBefore(endAt)) {
                throwIfCanceled(record)
                enforceRuntimeLimit(record)
                Thread.sleep(100)
            }
            appendBufferedRow(record, listOf("0"))
            return
        }

        val seriesMatch = Regex("generate_series\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)").find(normalizedSql)
        if (seriesMatch != null) {
            val start = seriesMatch.groupValues[1].toIntOrNull() ?: 1
            val end = seriesMatch.groupValues[2].toIntOrNull() ?: start
            replaceBufferedColumns(record, listOf(QueryResultColumn(name = "generate_series", jdbcType = "INTEGER")))
            for (value in start..end) {
                throwIfCanceled(record)
                enforceRuntimeLimit(record)
                if (!appendBufferedRow(record, listOf(value.toString()))) {
                    break
                }
            }
            return
        }

        val repeatMatch =
            Regex(
                "^select\\s+repeat\\(\\s*'([^']*)'\\s*,\\s*(\\d+)\\s*\\)(?:\\s+as\\s+([a-zA-Z_][a-zA-Z0-9_]*))?\\s*;?$",
                RegexOption.IGNORE_CASE,
            ).find(sql)
        if (repeatMatch != null) {
            val repeatedText = repeatMatch.groupValues[1]
            val requestedCount = repeatMatch.groupValues[2].toLongOrNull()?.coerceAtLeast(0) ?: 0L
            val repeatCount = requestedCount.coerceAtMost(record.maxCellBytes.toLong() + 64L).toInt()
            val alias = repeatMatch.groupValues.getOrNull(3)?.ifBlank { null } ?: "repeat"
            replaceBufferedColumns(record, listOf(QueryResultColumn(name = alias, jdbcType = "VARCHAR")))
            appendBufferedRow(record, listOf(repeatedText.repeat(repeatCount)))
            return
        }

        val selectOneMatch = Regex("^select\\s+1(?:\\s+as\\s+([a-zA-Z_][a-zA-Z0-9_]*))?\\s*;?$").find(normalizedSql)
        if (selectOneMatch != null) {
            val alias = selectOneMatch.groupValues.getOrNull(1)?.ifBlank { null } ?: "value"
            replaceBufferedColumns(record, listOf(QueryResultColumn(name = alias, jdbcType = "INTEGER")))
            appendBufferedRow(record, listOf("1"))
            return
        }

        replaceBufferedColumns(record, listOf(QueryResultColumn(name = "result", jdbcType = "VARCHAR")))
        appendBufferedRow(record, listOf("Query executed in local simulation mode."))
    }

    private fun markRunning(record: QueryExecutionRecord) {
        synchronized(record.lifecycleLock) {
            record.status = QueryExecutionStatus.RUNNING
            record.startedAt = Instant.now()
            record.message = "Query is running."
            queryRuntimeRepository.updateExecution(record.toRuntimeUpdate())
            syncHistoryOnly(record)
        }
        publishEvent(record, record.message)
        logger.info(
            "query_execution running executionId={} actor={} datasourceId={}",
            record.executionId,
            record.actor,
            record.datasourceId,
        )
    }

    private fun markSucceeded(record: QueryExecutionRecord) {
        synchronized(record.lifecycleLock) {
            if (record.status == QueryExecutionStatus.CANCELED || record.cancelRequested) {
                throw QueryCanceledException("Query canceled before result finalization.")
            }

            val completedAt = Instant.now()
            val message =
                record.resultLimitMessage
                    ?: if (record.rowLimitReached) {
                        "Query succeeded. Result truncated at ${record.maxRowsPerQuery} rows."
                    } else {
                        "Query succeeded."
                    }
            val finalPage = record.resultBuffer.snapshotPending()
            try {
                queryRuntimeRepository.finalizeSucceededExecution(
                    update =
                        record.toRuntimeUpdate(
                            status = QueryExecutionStatus.SUCCEEDED,
                            message = message,
                            completedAt = completedAt,
                        ),
                    finalPage = finalPage,
                )
            } catch (_: QueryRuntimeWriteRejectedException) {
                record.cancelRequested = true
                clearBufferedResults(record)
                throw QueryCanceledException("Query execution ownership was canceled before result finalization.")
            }
            if (finalPage != null) {
                releaseInstanceBufferedBytes(record.resultBuffer.acknowledge(finalPage))
            }
            record.status = QueryExecutionStatus.SUCCEEDED
            record.completedAt = completedAt
            record.lastAccessedAt = completedAt
            record.message = message
            syncHistoryOnly(record)
        }
        publishEvent(record, record.message)
        recordExecutionOutcomeMetric(record, "succeeded")
        logger.info(
            "query_execution succeeded executionId={} actor={} datasourceId={} rows={} rowLimitReached={}",
            record.executionId,
            record.actor,
            record.datasourceId,
            record.resultBuffer.totalRowCount(),
            record.rowLimitReached,
        )
    }

    private fun markFailed(
        record: QueryExecutionRecord,
        message: String,
    ) {
        synchronized(record.lifecycleLock) {
            if (record.status == QueryExecutionStatus.CANCELED) {
                return
            }

            record.status = QueryExecutionStatus.FAILED
            record.errorSummary = message
            record.message = "Query failed."
            record.completedAt = Instant.now()
            clearBufferedResults(record)
            queryRuntimeRepository.finalizeWithoutResults(record.toRuntimeUpdate())
            syncHistoryOnly(record)
        }
        publishEvent(record, message)
        recordExecutionOutcomeMetric(record, "failed")
        logger.warn(
            "query_execution failed executionId={} actor={} datasourceId={} errorSummary={}",
            record.executionId,
            record.actor,
            record.datasourceId,
            message,
        )
    }

    private fun markCanceled(
        record: QueryExecutionRecord,
        message: String,
    ) {
        synchronized(record.lifecycleLock) {
            if (record.status == QueryExecutionStatus.CANCELED) {
                return
            }
            record.status = QueryExecutionStatus.CANCELED
            record.cancelRequested = true
            record.errorSummary = null
            record.message = message
            record.completedAt = Instant.now()
            clearBufferedResults(record)
            queryRuntimeRepository.finalizeWithoutResults(record.toRuntimeUpdate())
            syncHistoryOnly(record)
        }
        publishEvent(record, message)
        recordExecutionOutcomeMetric(record, "canceled")
        logger.info(
            "query_execution canceled executionId={} actor={} datasourceId={} reason={}",
            record.executionId,
            record.actor,
            record.datasourceId,
            message,
        )
    }

    private fun resolveAuthorizedExecution(
        actor: String,
        isSystemAdmin: Boolean,
        executionId: String,
    ): QueryExecutionRecord {
        val record =
            executions[executionId]
                ?: throw QueryExecutionNotFoundException("Query execution '$executionId' was not found.")

        enforceExecutionAccess(actor, isSystemAdmin, record)
        return record
    }

    private fun enforceExecutionAccess(
        actor: String,
        isSystemAdmin: Boolean,
        record: QueryExecutionRecord,
    ) {
        if (!isSystemAdmin && record.actor != actor) {
            throw QueryExecutionForbiddenException("Query execution '${record.executionId}' is not accessible.")
        }
    }

    private fun enforceExecutionAccess(
        actor: String,
        isSystemAdmin: Boolean,
        record: PersistedQueryRuntimeRecord,
    ) {
        if (!isSystemAdmin && record.actor != actor) {
            throw QueryExecutionForbiddenException("Query execution '${record.executionId}' is not accessible.")
        }
    }

    private fun enforceExecutionAccess(
        actor: String,
        isSystemAdmin: Boolean,
        record: PersistedQueryRuntimeMetadataRecord,
    ) {
        if (!isSystemAdmin && record.actor != actor) {
            throw QueryExecutionForbiddenException("Query execution '${record.executionId}' is not accessible.")
        }
    }

    private fun requestRemoteCancel(
        actor: String,
        isSystemAdmin: Boolean,
        executionId: String,
    ): QueryCancelResponse {
        val persisted =
            queryRuntimeRepository.findMetadata(executionId)
                ?: throw QueryExecutionNotFoundException("Query execution '$executionId' was not found.")
        enforceExecutionAccess(actor, isSystemAdmin, persisted)
        if (persisted.status in terminalStatuses()) {
            return QueryCancelResponse(
                executionId = persisted.executionId,
                status = persisted.status.name,
                message = "Query already finished with status ${persisted.status.name}.",
                canceledAt = Instant.now().toString(),
            )
        }
        if (queryRuntimeRepository.markRemoteControlRequested(executionId, RemoteQueryControlAction.CANCEL)) {
            runCatching { queryLifecycleMetrics.recordRemoteRequest(RemoteQueryControlAction.CANCEL) }
        }
        return QueryCancelResponse(
            executionId = persisted.executionId,
            status = persisted.status.name,
            message = "Cancellation requested on backend instance ${persisted.ownerInstanceId}.",
            canceledAt = Instant.now().toString(),
        )
    }

    private fun requestRemoteKill(
        actor: String,
        executionId: String,
    ): QueryKillResponse {
        val persisted =
            queryRuntimeRepository.findMetadata(executionId)
                ?: throw QueryExecutionNotFoundException("Query execution '$executionId' was not found.")
        if (persisted.status in terminalStatuses()) {
            return QueryKillResponse(
                executionId = persisted.executionId,
                status = persisted.status.name,
                message = "Query already finished with status ${persisted.status.name}.",
                killedAt = Instant.now().toString(),
            )
        }
        if (queryRuntimeRepository.markRemoteControlRequested(executionId, RemoteQueryControlAction.KILL)) {
            runCatching { queryLifecycleMetrics.recordRemoteRequest(RemoteQueryControlAction.KILL) }
        }
        return QueryKillResponse(
            executionId = persisted.executionId,
            status = persisted.status.name,
            message = "Kill requested on backend instance ${persisted.ownerInstanceId}.",
            killedAt = Instant.now().toString(),
        )
    }

    private fun shouldUseSimulation(sql: String): Boolean {
        val normalizedSql = sql.trim().lowercase(Locale.getDefault())
        return normalizedSql.contains("pg_sleep(") ||
            normalizedSql.contains("generate_series(") ||
            Regex("^select\\s+repeat\\(").containsMatchIn(normalizedSql) ||
            Regex("^select\\s+1(?:\\s+as\\s+[a-zA-Z_][a-zA-Z0-9_]*)?\\s*;?$").matches(normalizedSql)
    }

    private fun enforceRuntimeLimit(record: QueryExecutionRecord) {
        val startedAt = record.startedAt ?: return
        val elapsedSeconds = Duration.between(startedAt, Instant.now()).seconds
        if (elapsedSeconds > record.maxRuntimeSeconds) {
            throw QueryRuntimeLimitException("Query timed out after ${record.maxRuntimeSeconds} seconds.")
        }
    }

    private fun throwIfCanceled(record: QueryExecutionRecord) {
        val legacyCancelRequested =
            !record.cancelRequested &&
                record.legacyCancelCheckGate.shouldCheck() &&
                runCatching { queryRuntimeRepository.isLegacyCancelRequested(record.executionId) }.getOrDefault(false)
        if (
            record.cancelRequested ||
            legacyCancelRequested ||
            Thread.currentThread().isInterrupted
        ) {
            record.cancelRequested = true
            throw QueryCanceledException("Query canceled.")
        }
    }

    private fun claimRemoteControlRequest(record: QueryExecutionRecord): RemoteQueryControlRequest? =
        queryRuntimeRepository
            .claimRemoteControlRequest(
                executionId = record.executionId,
                ownerInstanceId = applicationInstanceId.value,
            )?.also { request ->
                record.cancelRequested = true
                runCatching { queryLifecycleMetrics.recordRemoteObservation(request) }
            }

    private fun applyRemoteControl(
        record: QueryExecutionRecord,
        request: RemoteQueryControlRequest,
    ) {
        if (record.status in terminalStatuses()) {
            return
        }
        record.cancelRequested = true
        runCatching { record.activeStatement?.cancel() }
        closeActiveConnection(record)
        record.executionFuture?.cancel(true)
        if (record.status !in terminalStatuses()) {
            val message =
                when (request.action) {
                    RemoteQueryControlAction.CANCEL -> "Query canceled by a remote backend request."
                    RemoteQueryControlAction.KILL -> "Query killed by a remote admin request."
                }
            markCanceled(record, message)
        }
    }

    private fun closeRuntimeResources(record: QueryExecutionRecord) {
        runCatching { record.activeStatement?.close() }
        record.activeStatement = null
        closeActiveConnection(record)
    }

    private fun closeActiveConnection(record: QueryExecutionRecord) {
        val handle = record.activeConnectionHandle
        if (handle != null) {
            runCatching { handle.close(evict = record.activeConnectionRequiresEviction) }
        } else {
            runCatching { record.activeConnection?.close() }
        }
        record.activeConnectionHandle = null
        record.activeConnectionRequiresEviction = false
        record.activeConnection = null
    }

    private fun countExecutions(status: QueryExecutionStatus): Int =
        runCatching { queryRuntimeRepository.countActive(status) }
            .getOrElse { executions.values.count { record -> record.status == status } }

    private fun recordExecutionOutcomeMetric(
        record: QueryExecutionRecord,
        outcome: String,
    ) {
        meterRegistry
            .counter(
                "dwarvenpick.query.execution.total",
                "outcome",
                outcome,
                "datasourceId",
                record.datasourceId,
            ).increment()

        val startedAt = record.startedAt
        val completedAt = record.completedAt
        if (startedAt != null && completedAt != null) {
            val duration = Duration.between(startedAt, completedAt)
            if (!duration.isNegative) {
                meterRegistry
                    .timer(
                        "dwarvenpick.query.duration",
                        "outcome",
                        outcome,
                        "datasourceId",
                        record.datasourceId,
                    ).record(duration)
            }
        }
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
            queryRuntimeRepository.listActiveMetadata(
                actor = actor,
                isSystemAdmin = isSystemAdmin,
                datasourceId = null,
                actorFilter = null,
            )

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
                SseEmitter
                    .event()
                    .id(event.eventId)
                    .name("query-status")
                    .data(event),
            )
        }.onFailure { exception ->
            subscribers[actor]?.remove(emitter)
            runCatching { emitter.complete() }
            logger.debug("Dropping query status SSE emitter for actor={}", actor, exception)
        }
    }

    private fun sendHeartbeat(
        emitter: SseEmitter,
        actor: String,
    ) {
        val eventId = eventCounter.incrementAndGet().toString()
        runCatching {
            emitter.send(
                SseEmitter
                    .event()
                    .id(eventId)
                    .name("heartbeat")
                    .data("ping"),
            )
        }.onFailure { exception ->
            subscribers[actor]?.remove(emitter)
            runCatching { emitter.complete() }
            logger.debug("Dropping heartbeat SSE emitter for actor={}", actor, exception)
        }
    }

    private fun auditExecution(
        record: QueryExecutionRecord,
        type: String,
        outcome: String,
        details: Map<String, Any?>,
    ) {
        val enrichedDetails =
            details
                .toMutableMap()
                .apply {
                    putIfAbsent("executionId", record.executionId)
                    putIfAbsent("datasourceId", record.datasourceId)
                    putIfAbsent("credentialProfile", record.credentialProfile)
                    if (record.justification != null) {
                        putIfAbsent("justification", record.justification)
                    }
                }
        authAuditLogger.log(
            AuthAuditEvent(
                type = type,
                actor = record.actor,
                outcome = outcome,
                ipAddress = record.ipAddress,
                details = enrichedDetails,
            ),
        )
    }

    private fun syncHistoryOnly(record: QueryExecutionRecord) {
        saveHistory(record)
    }

    private fun saveHistory(record: QueryExecutionRecord) {
        val terminal = record.status in terminalStatuses()
        queryHistoryRepository.save(
            QueryHistoryRecord(
                executionId = record.executionId,
                actor = record.actor,
                datasourceId = record.datasourceId,
                credentialProfile = record.credentialProfile,
                defaultSchema = record.defaultSchema,
                justification = record.justification,
                queryHash = record.queryHash,
                queryText = record.sql,
                queryTextRedacted = false,
                status = record.status,
                message = record.message,
                errorSummary = record.errorSummary,
                rowCount = if (terminal) record.resultBuffer.totalRowCount() else 0,
                columnCount = if (terminal) record.resultBuffer.columns().size else 0,
                rowLimitReached = record.rowLimitReached,
                maxRowsPerQuery = record.maxRowsPerQuery,
                maxRuntimeSeconds = record.maxRuntimeSeconds,
                submittedAt = record.submittedAt,
                startedAt = record.startedAt,
                completedAt = record.completedAt,
            ),
        )
    }

    private fun QueryExecutionRecord.toPersistedRuntimeRecord(): PersistedQueryRuntimeRecord =
        PersistedQueryRuntimeRecord(
            executionId = executionId,
            actor = actor,
            ipAddress = ipAddress,
            datasourceId = datasourceId,
            credentialProfile = credentialProfile,
            defaultSchema = defaultSchema,
            justification = justification,
            sql = sql,
            sqlRedacted = false,
            queryHash = queryHash,
            maxRowsPerQuery = maxRowsPerQuery,
            maxRuntimeSeconds = maxRuntimeSeconds,
            concurrencyLimit = concurrencyLimit,
            scriptStatementCount = scriptStatementCount,
            scriptStopOnError = scriptStopOnError,
            scriptTransactionMode = scriptTransactionMode,
            scriptStatements = scriptStatements.toList(),
            status = status,
            message = message,
            errorSummary = errorSummary,
            submittedAt = submittedAt,
            startedAt = startedAt,
            completedAt = completedAt,
            rowLimitReached = rowLimitReached,
            columns = emptyList(),
            rows = emptyList(),
            lastAccessedAt = lastAccessedAt,
            resultsExpired = resultsExpired,
            cancelRequested = cancelRequested,
            ownerInstanceId = applicationInstanceId.value,
            heartbeatAt = Instant.now(),
        )

    private fun QueryExecutionRecord.toRuntimeUpdate(
        status: QueryExecutionStatus = this.status,
        message: String = this.message,
        completedAt: Instant? = this.completedAt,
    ): QueryRuntimeExecutionUpdate {
        val terminal = status in terminalStatuses()
        return QueryRuntimeExecutionUpdate(
            executionId = executionId,
            ownerInstanceId = applicationInstanceId.value,
            status = status,
            message = message,
            errorSummary = errorSummary,
            startedAt = startedAt,
            completedAt = completedAt,
            rowCount = if (terminal && status == QueryExecutionStatus.SUCCEEDED) resultBuffer.totalRowCount() else 0,
            columnCount = if (terminal && status == QueryExecutionStatus.SUCCEEDED) resultBuffer.columns().size else 0,
            rowLimitReached = rowLimitReached,
            columns = if (terminal && status == QueryExecutionStatus.SUCCEEDED) resultBuffer.columns() else emptyList(),
            scriptStatements = scriptStatements.toList(),
            lastAccessedAt = if (status == QueryExecutionStatus.SUCCEEDED) completedAt ?: lastAccessedAt else lastAccessedAt,
            cancelRequested = cancelRequested,
            heartbeatAt = Instant.now(),
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

    private fun normalizeQueryJustification(justification: String?): String? {
        val normalized =
            justification
                ?.replace(controlCharacterRegex, " ")
                ?.replace(whitespaceRegex, " ")
                ?.trim()
                ?.takeIf { value -> value.isNotBlank() }
                ?: return null
        val maxLength = queryExecutionProperties.maxJustificationLength.coerceAtLeast(1)
        if (normalized.length > maxLength) {
            throw IllegalArgumentException("justification must be $maxLength characters or fewer.")
        }
        return normalized
    }

    private fun enforceWriteJustification(
        actor: String,
        ipAddress: String?,
        datasourceId: String,
        policy: QueryAccessPolicy,
        hasWriteIntent: Boolean,
        justification: String?,
    ) {
        if (!queryJustificationPolicy.requiresJustification(policy.readOnly) || justification != null) {
            return
        }

        meterRegistry
            .counter(
                "dwarvenpick.query.execute.attempts",
                "outcome",
                "blocked_missing_justification",
                "datasourceId",
                datasourceId,
            ).increment()
        authAuditLogger.log(
            AuthAuditEvent(
                type = "query.execute",
                actor = actor,
                outcome = "denied",
                ipAddress = ipAddress,
                details =
                    mapOf(
                        "datasourceId" to datasourceId,
                        "credentialProfile" to policy.credentialProfile,
                        "readOnly" to policy.readOnly,
                        "writeLikeSql" to hasWriteIntent,
                        "reason" to "missing_write_justification",
                    ),
            ),
        )
        throw QueryJustificationRequiredException(
            "A justification is required for non-read-only credential profiles or write-like scripts.",
        )
    }

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
            rowCount = if (status in terminalStatuses()) resultBuffer.totalRowCount() else 0,
            columnCount = if (status in terminalStatuses()) resultBuffer.columns().size else 0,
            rowLimitReached = rowLimitReached,
            maxRowsPerQuery = maxRowsPerQuery,
            maxRuntimeSeconds = maxRuntimeSeconds,
            credentialProfile = credentialProfile,
            justification = justification,
            scriptSummary =
                if (scriptStatementCount > 1 || scriptStatements.isNotEmpty()) {
                    QueryScriptSummary(
                        statementCount = scriptStatementCount,
                        stopOnError = scriptStopOnError,
                        transactionMode = scriptTransactionMode.name,
                        statements = scriptStatements.toList(),
                    )
                } else {
                    null
                },
        )

    private fun PersistedQueryRuntimeRecord.toStatusResponse(): QueryExecutionStatusResponse =
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
            justification = justification,
            scriptSummary =
                if (scriptStatementCount > 1 || scriptStatements.isNotEmpty()) {
                    QueryScriptSummary(
                        statementCount = scriptStatementCount,
                        stopOnError = scriptStopOnError,
                        transactionMode = scriptTransactionMode.name,
                        statements = scriptStatements,
                    )
                } else {
                    null
                },
        )

    private fun PersistedQueryRuntimeMetadataRecord.toStatusResponse(): QueryExecutionStatusResponse =
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
            rowCount = rowCount,
            columnCount = columnCount,
            rowLimitReached = rowLimitReached,
            maxRowsPerQuery = maxRowsPerQuery,
            maxRuntimeSeconds = maxRuntimeSeconds,
            credentialProfile = credentialProfile,
            justification = justification,
            scriptSummary =
                if (scriptStatementCount > 1 || scriptStatements.isNotEmpty()) {
                    QueryScriptSummary(
                        statementCount = scriptStatementCount,
                        stopOnError = scriptStopOnError,
                        transactionMode = scriptTransactionMode.name,
                        statements = scriptStatements,
                    )
                } else {
                    null
                },
        )

    private companion object {
        private const val MAX_FIELD_SIZE_DETECTION_BYTES = 4L
        private val controlCharacterRegex = Regex("[\\u0000-\\u001F\\u007F]+")
        private val whitespaceRegex = Regex("\\s+")
    }
}
