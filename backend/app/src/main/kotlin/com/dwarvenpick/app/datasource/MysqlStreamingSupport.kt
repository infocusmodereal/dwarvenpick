package com.dwarvenpick.app.datasource

private const val MYSQL_CONNECTOR_J_DRIVER = "com.mysql.cj.jdbc.Driver"

internal fun shouldUseMysqlConnectorStreaming(
    engine: DatasourceEngine,
    driverClass: String,
): Boolean =
    driverClass == MYSQL_CONNECTOR_J_DRIVER &&
        engine in setOf(DatasourceEngine.MYSQL, DatasourceEngine.STARROCKS)
