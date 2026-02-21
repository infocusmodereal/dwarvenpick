package com.dwarvenpick.app.datasource

data class DatasourceSchemaBrowserResponse(
    val datasourceId: String,
    val cached: Boolean,
    val fetchedAt: String,
    val schemas: List<DatasourceSchemaEntryResponse>,
)

data class DatasourceSchemaEntryResponse(
    val schema: String,
    val tables: List<DatasourceTableEntryResponse>,
)

data class DatasourceTableEntryResponse(
    val table: String,
    val type: String,
    val columns: List<DatasourceColumnEntryResponse>,
)

data class DatasourceColumnEntryResponse(
    val name: String,
    val jdbcType: String,
    val nullable: Boolean,
)
