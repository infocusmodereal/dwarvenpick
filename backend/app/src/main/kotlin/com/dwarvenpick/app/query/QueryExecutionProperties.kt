package com.dwarvenpick.app.query

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dwarvenpick.query")
data class QueryExecutionProperties(
    val defaultPageSize: Int = 500,
    val maxPageSize: Int = 1000,
    val resultChunkRows: Int = 500,
    val maxBufferedRows: Int = 5000,
    val maxBufferedBytes: Long = 64L * 1024L * 1024L,
    val maxBufferedBytesPerInstance: Long = 256L * 1024L * 1024L,
    val maxCellBytes: Int = 1024 * 1024,
    val maxExportRows: Int = 5000,
    val jdbcFetchSize: Int = 500,
    val maxConcurrencyPerUser: Int = 3,
    val maxConcurrencyPerConnection: Int = 10,
    val maxConcurrencyGlobal: Int = 50,
    val resultSessionTtlSeconds: Long = 600,
    val executionRetentionSeconds: Long = 3600,
    val cancelGracePeriodMs: Long = 500,
    val cleanupIntervalMs: Long = 30_000,
    val shutdownGracePeriodSeconds: Long = 15,
    val historyRetentionDays: Long = 30,
    val auditRetentionDays: Long = 90,
    val queryTextRedactionDays: Long = 7,
    val retentionCleanupIntervalMs: Long = 3_600_000,
    val activeExecutionStaleSeconds: Long = 120,
    val poolMetricsStaleSeconds: Long = 120,
    val requireWriteJustification: Boolean = false,
    val maxJustificationLength: Int = 1000,
)
