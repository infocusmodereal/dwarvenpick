package com.dwarvenpick.app.systemhealth

import com.dwarvenpick.app.datasource.ConnectionSpec
import com.dwarvenpick.app.datasource.DatasourceEngine
import java.sql.Connection
import java.sql.SQLException

data class SystemHealthCheckResult(
    val status: SystemHealthStatus,
    val message: String?,
    val nodes: List<SystemHealthNode> = emptyList(),
    val details: Map<String, Any?> = emptyMap(),
)

interface SystemHealthProvider {
    val engines: Set<DatasourceEngine>

    fun check(
        spec: ConnectionSpec,
        connection: Connection,
    ): SystemHealthCheckResult
}

fun isInsufficientPrivilege(exception: SQLException): Boolean {
    val sqlState = exception.sqlState?.trim()?.uppercase()
    if (sqlState == "42501" || sqlState == "28000") {
        return true
    }

    val message = exception.message?.lowercase() ?: return false
    return message.contains("permission denied") ||
        message.contains("insufficient privilege") ||
        message.contains("access denied") ||
        message.contains("not authorized") ||
        message.contains("denied")
}
