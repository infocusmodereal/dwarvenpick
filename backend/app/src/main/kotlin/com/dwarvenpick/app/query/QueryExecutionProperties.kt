package com.dwarvenpick.app.query

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dwarvenpick.query")
data class QueryExecutionProperties(
    val defaultPageSize: Int = 500,
    val maxPageSize: Int = 1000,
    val maxBufferedRows: Int = 5000,
    val maxExportRows: Int = 5000,
    val maxConcurrencyPerUser: Int = 3,
    val resultSessionTtlSeconds: Long = 600,
    val executionRetentionSeconds: Long = 3600,
    val cancelGracePeriodMs: Long = 500,
    val cleanupIntervalMs: Long = 30_000,
    val shutdownGracePeriodSeconds: Long = 15,
    val historyRetentionDays: Long = 30,
    val auditRetentionDays: Long = 90,
    val queryTextRedactionDays: Long = 0,
    val retentionCleanupIntervalMs: Long = 3_600_000,
)
