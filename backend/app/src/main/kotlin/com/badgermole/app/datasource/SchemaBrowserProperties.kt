package com.badgermole.app.datasource

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "badgermole.schema")
data class SchemaBrowserProperties(
    val cacheTtlSeconds: Long = 300,
    val maxSchemas: Int = 20,
    val maxTablesPerSchema: Int = 100,
    val maxColumnsPerTable: Int = 200,
)
