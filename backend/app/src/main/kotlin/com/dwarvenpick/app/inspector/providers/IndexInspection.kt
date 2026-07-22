package com.dwarvenpick.app.inspector.providers

import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.inspector.InspectedObjectType
import com.dwarvenpick.app.inspector.ObjectInspectorTable
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.Locale

private val INDEX_COLUMNS =
    listOf(
        "name",
        "non_unique",
        "seq_in_index",
        "column_name",
        "index_type",
        "nullable",
        "cardinality",
        "comment",
    )

private const val MYSQL_INDEX_SQL =
    """
    SELECT index_name AS name,
           non_unique AS non_unique,
           seq_in_index AS seq_in_index,
           column_name AS column_name,
           index_type AS index_type,
           nullable AS nullable,
           cardinality AS cardinality,
           comment AS comment
    FROM information_schema.statistics
    WHERE table_schema = ? AND table_name = ?
    ORDER BY index_name, seq_in_index
    """

internal data class IndexInspectionPlan(
    val sql: String,
    val bindSchemaAndName: Boolean,
    val normalizeStarRocksResult: Boolean,
)

internal fun buildIndexInspectionPlan(
    engine: DatasourceEngine,
    objectType: InspectedObjectType,
    schema: String,
    name: String,
): IndexInspectionPlan =
    if (engine == DatasourceEngine.STARROCKS && objectType == InspectedObjectType.TABLE) {
        IndexInspectionPlan(
            sql = "SHOW INDEX FROM ${buildQualifiedName("`", listOf(schema, name))}",
            bindSchemaAndName = false,
            normalizeStarRocksResult = true,
        )
    } else {
        IndexInspectionPlan(
            sql = MYSQL_INDEX_SQL.trimIndent(),
            bindSchemaAndName = true,
            normalizeStarRocksResult = false,
        )
    }

internal fun loadIndexTable(
    connection: Connection,
    plan: IndexInspectionPlan,
    schema: String,
    name: String,
): ObjectInspectorTable {
    val table =
        runTableQuery(
            connection = connection,
            sql = plan.sql,
            bind = { statement -> bindIndexInspectionParameters(statement, plan, schema, name) },
        )
    return if (plan.normalizeStarRocksResult) normalizeStarRocksIndexTable(table) else table
}

internal fun bindIndexInspectionParameters(
    statement: PreparedStatement,
    plan: IndexInspectionPlan,
    schema: String,
    name: String,
) {
    if (plan.bindSchemaAndName) {
        statement.setString(1, schema)
        statement.setString(2, name)
    }
}

internal fun normalizeStarRocksIndexTable(table: ObjectInspectorTable): ObjectInspectorTable {
    val sourceIndexes =
        table.columns
            .mapIndexed { index, label -> label.trim().lowercase(Locale.ROOT) to index }
            .toMap()
    val targetIndexes =
        listOf(
            sourceIndexes.firstIndex("key_name", "index_name", "name"),
            sourceIndexes.firstIndex("non_unique"),
            sourceIndexes.firstIndex("seq_in_index"),
            sourceIndexes.firstIndex("column_name"),
            sourceIndexes.firstIndex("index_type"),
            sourceIndexes.firstIndex("null", "nullable"),
            sourceIndexes.firstIndex("cardinality"),
            sourceIndexes.firstIndex("comment", "index_comment"),
        )

    val normalizedRows =
        table.rows
            .map { row ->
                targetIndexes.map { sourceIndex -> sourceIndex?.let(row::getOrNull) }
            }.sortedWith(
                compareBy<List<String?>>(
                    { it[0].orEmpty().lowercase(Locale.ROOT) },
                    { it[2]?.toIntOrNull() ?: Int.MAX_VALUE },
                    { it[2].orEmpty() },
                ),
            )

    return table.copy(columns = INDEX_COLUMNS, rows = normalizedRows)
}

private fun Map<String, Int>.firstIndex(vararg labels: String): Int? = labels.firstNotNullOfOrNull { label -> this[label] }
