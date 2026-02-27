package com.dwarvenpick.app.datasource

import jakarta.validation.constraints.NotBlank

enum class MavenDriverPreset(
    val engine: DatasourceEngine,
    val groupId: String,
    val artifactId: String,
    val driverClass: String,
    val defaultDescription: String,
) {
    POSTGRESQL(
        engine = DatasourceEngine.POSTGRESQL,
        groupId = "org.postgresql",
        artifactId = "postgresql",
        driverClass = "org.postgresql.Driver",
        defaultDescription = "PostgreSQL JDBC driver",
    ),
    MYSQL(
        engine = DatasourceEngine.MYSQL,
        groupId = "com.mysql",
        artifactId = "mysql-connector-j",
        driverClass = "com.mysql.cj.jdbc.Driver",
        defaultDescription = "MySQL Connector/J",
    ),
    MARIADB(
        engine = DatasourceEngine.MARIADB,
        groupId = "org.mariadb.jdbc",
        artifactId = "mariadb-java-client",
        driverClass = "org.mariadb.jdbc.Driver",
        defaultDescription = "MariaDB JDBC driver",
    ),
    TRINO(
        engine = DatasourceEngine.TRINO,
        groupId = "io.trino",
        artifactId = "trino-jdbc",
        driverClass = "io.trino.jdbc.TrinoDriver",
        defaultDescription = "Trino JDBC driver",
    ),
    STARROCKS_MYSQL(
        engine = DatasourceEngine.STARROCKS,
        groupId = "com.mysql",
        artifactId = "mysql-connector-j",
        driverClass = "com.mysql.cj.jdbc.Driver",
        defaultDescription = "StarRocks via MySQL protocol (MySQL Connector/J)",
    ),
    STARROCKS_MARIADB(
        engine = DatasourceEngine.STARROCKS,
        groupId = "org.mariadb.jdbc",
        artifactId = "mariadb-java-client",
        driverClass = "org.mariadb.jdbc.Driver",
        defaultDescription = "StarRocks via MySQL protocol (MariaDB JDBC driver)",
    ),
}

data class InstallMavenDriverRequest(
    val preset: MavenDriverPreset,
    @field:NotBlank(message = "version is required.")
    val version: String,
    val driverId: String? = null,
    val description: String? = null,
)
