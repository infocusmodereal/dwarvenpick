package com.dwarvenpick.app.datasource

data class DatasourceSchemaBrowserResponse(
    val datasourceId: String,
    val cached: Boolean,
    val fetchedAt: String,
    val schemas: List<DatasourceSchemaEntryResponse>,
    val searchTruncated: Boolean = false,
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

data class SchemaBrowserTable(
    val schema: String,
    val table: String,
) {
    init {
        require(schema.isNotBlank() && table.isNotBlank()) { "Schema and table names are required." }
        require(listOf(schema, table).all { it.length <= 512 && it.none(Char::isISOControl) }) { "Invalid schema or table name." }
    }
}

data class SchemaBrowserSelection(
    val includeColumns: Boolean = true,
    val table: SchemaBrowserTable? = null,
    val schema: String? = null,
    val schemasOnly: Boolean = false,
    val search: String? = null,
) {
    init {
        require(schema == null || (schema.isNotBlank() && schema.length <= 512 && schema.none(Char::isISOControl))) {
            "Invalid schema name."
        }
        require(search == null || (search.isNotBlank() && search.length <= 128 && search.none(Char::isISOControl))) {
            "Search must contain 1 to 128 characters."
        }
        require(listOf(table != null, schema != null, schemasOnly, search != null).count { it } <= 1) {
            "Choose one metadata scope."
        }
    }
}
