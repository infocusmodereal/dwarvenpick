package com.badgermole.app.query

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "badgermole.query")
data class QueryExecutionProperties(
    val defaultPageSize: Int = 100,
    val maxPageSize: Int = 500,
    val maxBufferedRows: Int = 5000,
    val maxConcurrencyPerUser: Int = 3,
    val resultSessionTtlSeconds: Long = 600,
    val executionRetentionSeconds: Long = 3600,
    val cancelGracePeriodMs: Long = 500,
    val cleanupIntervalMs: Long = 30_000,
)
