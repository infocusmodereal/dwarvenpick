package com.dwarvenpick.app.controlplane

import com.dwarvenpick.app.datasource.DatasourceEngine

data class ControlPlaneLatencySummary(
    val windowSeconds: Long,
    val sampleSize: Int,
    val succeededCount: Int,
    val failedCount: Int,
    val canceledCount: Int,
    val averageMs: Long?,
    val p50Ms: Long?,
    val p90Ms: Long?,
    val maxMs: Long?,
    val latestErrors: List<String>,
)

data class ControlPlaneDatasourceStatusResponse(
    val datasourceId: String,
    val datasourceName: String,
    val engine: DatasourceEngine,
    val paused: Boolean,
    val fetchedAt: String,
    val queuedCount: Int,
    val runningCount: Int,
    val pools: List<ControlPlanePoolStatus>,
    val activeQueries: List<ControlPlaneActiveQuery>,
    val latency: ControlPlaneLatencySummary,
)

data class ControlPlanePoolStatus(
    val datasourceId: String,
    val credentialProfile: String,
    val activeConnections: Int,
    val idleConnections: Int,
    val totalConnections: Int,
    val maximumPoolSize: Int,
    val threadsAwaitingConnection: Int,
)

data class ControlPlaneActiveQuery(
    val executionId: String,
    val actor: String,
    val datasourceId: String,
    val credentialProfile: String,
    val status: String,
    val message: String,
    val queryHash: String,
    val sqlPreview: String,
    val submittedAt: String,
    val startedAt: String?,
    val durationMs: Long?,
    val cancelRequested: Boolean,
)

data class ControlPlaneBulkActionResponse(
    val datasourceId: String,
    val action: String,
    val matched: Int,
    val succeeded: Int,
    val failed: Int,
)

