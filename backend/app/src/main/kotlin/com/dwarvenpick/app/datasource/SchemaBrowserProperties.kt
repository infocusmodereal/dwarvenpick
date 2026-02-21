package com.dwarvenpick.app.datasource

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dwarvenpick.schema")
data class SchemaBrowserProperties(
    val cacheTtlSeconds: Long = 300,
    val maxSchemas: Int = 20,
    val maxTablesPerSchema: Int = 100,
    val maxColumnsPerTable: Int = 200,
)
