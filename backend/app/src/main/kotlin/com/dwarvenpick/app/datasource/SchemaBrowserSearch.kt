package com.dwarvenpick.app.datasource

internal class SchemaBrowserSearch(
    val term: String,
    properties: SchemaBrowserProperties,
) {
    private var remainingProbes = properties.maxSearchTables.coerceAtLeast(1)
    private var remainingMatches = properties.maxSearchMatches.coerceAtLeast(1)
    var truncated = false
        private set

    fun matches(name: String): Boolean = name.contains(term, ignoreCase = true)

    fun hasCapacity(): Boolean {
        if (remainingMatches > 0) return true
        truncated = true
        return false
    }

    fun filter(
        entry: DatasourceSchemaEntryResponse,
        findColumns: ((List<String>) -> Map<String, List<DatasourceColumnEntryResponse>>)? = null,
    ): DatasourceSchemaEntryResponse? {
        if (!hasCapacity()) return null
        val schemaMatches = matches(entry.schema)
        val namedTables = if (schemaMatches) entry.tables else entry.tables.filter { matches(it.table) }
        val tableMatches = namedTables.take(remainingMatches - 1)
        remainingMatches -= tableMatches.size
        if (namedTables.size > tableMatches.size) truncated = true

        val columnMatches =
            if (schemaMatches || findColumns == null) {
                emptyList()
            } else {
                matchingColumns(entry.tables.filterNot { matches(it.table) }, findColumns)
            }

        val tables = (tableMatches + columnMatches).sortedBy { it.table }
        if (!schemaMatches && tables.isEmpty()) return null
        remainingMatches--
        return entry.copy(tables = tables)
    }

    private fun matchingColumns(
        tables: List<DatasourceTableEntryResponse>,
        findColumns: (List<String>) -> Map<String, List<DatasourceColumnEntryResponse>>,
    ): List<DatasourceTableEntryResponse> {
        if (tables.isEmpty()) return emptyList()
        if (remainingProbes == 0 || remainingMatches <= 1) {
            truncated = true
            return emptyList()
        }
        val candidates = tables.take(remainingProbes)
        if (candidates.size < tables.size) truncated = true
        remainingProbes -= candidates.size
        val columnsByTable = findColumns(candidates.map { it.table })
        return candidates.mapNotNull { table ->
            val matches = columnsByTable[table.table].orEmpty().filter { matches(it.name) }
            if (matches.isEmpty()) return@mapNotNull null
            val columns = matches.take((remainingMatches - 2).coerceAtLeast(0))
            if (columns.size < matches.size) truncated = true
            if (columns.isEmpty()) return@mapNotNull null
            remainingMatches -= columns.size + 1
            table.copy(columns = columns)
        }
    }
}
