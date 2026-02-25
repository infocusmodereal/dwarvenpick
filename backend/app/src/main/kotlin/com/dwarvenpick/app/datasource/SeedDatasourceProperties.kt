package com.dwarvenpick.app.datasource

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dwarvenpick.seed")
data class SeedDatasourceProperties(
    val enabled: Boolean = true,
    val postgres: SeedJdbcProperties =
        SeedJdbcProperties(
            host = "localhost",
            port = 5432,
            database = "dwarvenpick",
            username = "dwarvenpick",
            password = "dwarvenpick",
        ),
    val mysql: SeedJdbcProperties =
        SeedJdbcProperties(
            host = "localhost",
            port = 3306,
            database = "orders",
            username = "readonly",
            password = "readonly",
        ),
    val mariadb: SeedJdbcProperties =
        SeedJdbcProperties(
            host = "localhost",
            port = 3306,
            database = "warehouse",
            username = "readonly",
            password = "readonly",
        ),
    val trino: SeedTrinoProperties = SeedTrinoProperties(),
    val starrocks: SeedJdbcProperties =
        SeedJdbcProperties(
            host = "localhost",
            port = 9030,
            database = "warehouse",
            username = "readonly",
            password = "readonly",
        ),
)

data class SeedJdbcProperties(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
)

data class SeedTrinoProperties(
    val enabled: Boolean = true,
    val host: String = "localhost",
    val port: Int = 8088,
    val database: String = "hive.default",
    val username: String = "trino",
    val password: String = "trino",
)
