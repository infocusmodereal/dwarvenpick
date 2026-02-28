package com.dwarvenpick.app.systemhealth

import com.dwarvenpick.app.datasource.DatasourceEngine

enum class SystemHealthStatus {
    OK,
    INSUFFICIENT_PRIVILEGES,
    UNSUPPORTED,
    ERROR,
}

data class SystemHealthNode(
    val name: String,
    val role: String?,
    val status: String,
    val details: Map<String, Any?> = emptyMap(),
)

data class SystemHealthResponse(
    val datasourceId: String,
    val datasourceName: String,
    val engine: DatasourceEngine,
    val credentialProfile: String,
    val checkedAt: String,
    val status: SystemHealthStatus,
    val message: String?,
    val nodeCount: Int,
    val healthyNodeCount: Int,
    val nodes: List<SystemHealthNode> = emptyList(),
    val details: Map<String, Any?> = emptyMap(),
)
