package com.dwarvenpick.app.controlplane

import com.dwarvenpick.app.datasource.DatasourcePoolManager
import com.dwarvenpick.app.datasource.DatasourceRegistryService
import com.dwarvenpick.app.datasource.ManagedDatasourceNotFoundException
import com.dwarvenpick.app.query.QueryExecutionManager
import com.dwarvenpick.app.query.QueryExecutionStatus
import com.dwarvenpick.app.query.QueryKillResponse
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.math.ceil

@Service
class ControlPlaneService(
    private val datasourceRegistryService: DatasourceRegistryService,
    private val datasourcePoolManager: DatasourcePoolManager,
    private val queryExecutionManager: QueryExecutionManager,
    private val datasourcePauseService: DatasourcePauseService,
) {
    fun status(
        actor: String,
        datasourceId: String,
        windowSeconds: Long,
    ): ControlPlaneDatasourceStatusResponse {
        val normalizedDatasourceId = datasourceId.trim()
        if (normalizedDatasourceId.isBlank()) {
            throw IllegalArgumentException("datasourceId is required.")
        }

        val catalog =
            datasourceRegistryService.listCatalogEntries()
                .firstOrNull { entry -> entry.id == normalizedDatasourceId }
                ?: throw ManagedDatasourceNotFoundException("Datasource '$normalizedDatasourceId' was not found.")

        val now = Instant.now()
        val window = windowSeconds.coerceIn(60, 24 * 60 * 60)
        val from = now.minusSeconds(window)

        val poolMetrics =
            datasourcePoolManager.listPoolMetrics()
                .filter { metric -> metric.datasourceId == normalizedDatasourceId }
                .map { metric ->
                    ControlPlanePoolStatus(
                        datasourceId = metric.datasourceId,
                        credentialProfile = metric.credentialProfile,
                        activeConnections = metric.activeConnections,
                        idleConnections = metric.idleConnections,
                        totalConnections = metric.totalConnections,
                        maximumPoolSize = metric.maximumPoolSize,
                        threadsAwaitingConnection = metric.threadsAwaitingConnection,
                    )
                }

        val activeQueries =
            queryExecutionManager
                .listActiveExecutions(
                    actor = actor,
                    isSystemAdmin = true,
                    datasourceId = normalizedDatasourceId,
                ).map { query ->
                    ControlPlaneActiveQuery(
                        executionId = query.executionId,
                        actor = query.actor,
                        datasourceId = query.datasourceId,
                        credentialProfile = query.credentialProfile,
                        status = query.status,
                        message = query.message,
                        queryHash = query.queryHash,
                        sqlPreview = query.sqlPreview,
                        submittedAt = query.submittedAt,
                        startedAt = query.startedAt,
                        durationMs = query.durationMs,
                        cancelRequested = query.cancelRequested,
                    )
                }

        val queuedCount = activeQueries.count { query -> query.status == QueryExecutionStatus.QUEUED.name }
        val runningCount = activeQueries.count { query -> query.status == QueryExecutionStatus.RUNNING.name }

        val history =
            queryExecutionManager.listHistory(
                actor = actor,
                isSystemAdmin = true,
                datasourceId = normalizedDatasourceId,
                status = null,
                from = from,
                to = now,
                limit = 1000,
                actorFilter = null,
            )

        val durations =
            history
                .mapNotNull { entry -> entry.durationMs }
                .filter { duration -> duration >= 0 }
                .sorted()

        val succeededCount = history.count { entry -> entry.status == QueryExecutionStatus.SUCCEEDED.name }
        val failedCount = history.count { entry -> entry.status == QueryExecutionStatus.FAILED.name }
        val canceledCount = history.count { entry -> entry.status == QueryExecutionStatus.CANCELED.name }

        val latestErrors =
            history
                .asSequence()
                .filter { entry -> entry.status == QueryExecutionStatus.FAILED.name }
                .map { entry -> entry.errorSummary ?: entry.message }
                .filter { message -> message.isNotBlank() }
                .distinct()
                .take(5)
                .toList()

        val latency =
            ControlPlaneLatencySummary(
                windowSeconds = window,
                sampleSize = durations.size,
                succeededCount = succeededCount,
                failedCount = failedCount,
                canceledCount = canceledCount,
                averageMs = durations.averageOrNull(),
                p50Ms = durations.percentileOrNull(0.50),
                p90Ms = durations.percentileOrNull(0.90),
                maxMs = durations.lastOrNull(),
                latestErrors = latestErrors,
            )

        return ControlPlaneDatasourceStatusResponse(
            datasourceId = catalog.id,
            datasourceName = catalog.name,
            engine = catalog.engine,
            paused = datasourcePauseService.isPaused(catalog.id),
            fetchedAt = now.toString(),
            queuedCount = queuedCount,
            runningCount = runningCount,
            pools = poolMetrics,
            activeQueries = activeQueries.sortedWith(compareBy({ it.status }, { it.submittedAt })),
            latency = latency,
        )
    }

    fun pause(datasourceId: String) {
        datasourcePauseService.pause(datasourceId)
    }

    fun resume(datasourceId: String) {
        datasourcePauseService.resume(datasourceId)
    }

    fun cancelAll(
        actor: String,
        datasourceId: String,
        targetActor: String?,
    ): ControlPlaneBulkActionResponse =
        bulkAction(
            action = "cancel",
            actor = actor,
            datasourceId = datasourceId,
            targetActor = targetActor,
        ) { executionId ->
            queryExecutionManager.cancelQuery(
                actor = actor,
                isSystemAdmin = true,
                executionId = executionId,
            )
        }

    fun killAll(
        actor: String,
        datasourceId: String,
        targetActor: String?,
    ): ControlPlaneBulkActionResponse =
        bulkAction(
            action = "kill",
            actor = actor,
            datasourceId = datasourceId,
            targetActor = targetActor,
        ) { executionId ->
            queryExecutionManager.killQuery(
                actor = actor,
                isSystemAdmin = true,
                executionId = executionId,
            )
        }

    fun killQuery(
        actor: String,
        executionId: String,
    ): QueryKillResponse =
        queryExecutionManager.killQuery(
            actor = actor,
            isSystemAdmin = true,
            executionId = executionId,
        )

    private fun bulkAction(
        action: String,
        actor: String,
        datasourceId: String,
        targetActor: String?,
        executor: (executionId: String) -> Any,
    ): ControlPlaneBulkActionResponse {
        val normalizedDatasourceId = datasourceId.trim()
        if (normalizedDatasourceId.isBlank()) {
            throw IllegalArgumentException("datasourceId is required.")
        }

        val normalizedTargetActor = targetActor?.trim()?.takeIf { value -> value.isNotBlank() }
        val active =
            queryExecutionManager.listActiveExecutions(
                actor = actor,
                isSystemAdmin = true,
                datasourceId = normalizedDatasourceId,
                actorFilter = normalizedTargetActor,
            )

        val executionIds =
            active
                .filter { record ->
                    record.status == QueryExecutionStatus.QUEUED.name || record.status == QueryExecutionStatus.RUNNING.name
                }.map { record -> record.executionId }
                .distinct()

        var succeeded = 0
        var failed = 0
        executionIds.forEach { executionId ->
            runCatching { executor(executionId) }
                .onSuccess { succeeded += 1 }
                .onFailure { failed += 1 }
        }

        return ControlPlaneBulkActionResponse(
            datasourceId = normalizedDatasourceId,
            action = action,
            matched = executionIds.size,
            succeeded = succeeded,
            failed = failed,
        )
    }

    private fun List<Long>.averageOrNull(): Long? {
        if (isEmpty()) {
            return null
        }
        val total = fold(0.0) { acc, value -> acc + value }
        return (total / size.toDouble()).toLong()
    }

    private fun List<Long>.percentileOrNull(p: Double): Long? {
        if (isEmpty()) {
            return null
        }
        val clamped = p.coerceIn(0.0, 1.0)
        val rank = ceil(clamped * size.toDouble()).toInt().coerceIn(1, size)
        return this[rank - 1]
    }
}
