package com.dwarvenpick.app.inspector.providers

import com.dwarvenpick.app.datasource.ConnectionSpec
import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.inspector.InspectedObjectNotFoundException
import com.dwarvenpick.app.inspector.InspectedObjectType
import com.dwarvenpick.app.inspector.ObjectInspectorKeyValue
import com.dwarvenpick.app.inspector.ObjectInspectorObjectRef
import com.dwarvenpick.app.inspector.ObjectInspectorProvider
import com.dwarvenpick.app.inspector.ObjectInspectorSection
import com.dwarvenpick.app.inspector.ObjectInspectorSectionKind
import com.dwarvenpick.app.inspector.ObjectInspectorSectionStatus
import com.dwarvenpick.app.inspector.ObjectInspectorTable
import com.dwarvenpick.app.systemhealth.isInsufficientPrivilege
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Locale

@Component
class AerospikeObjectInspectorProvider : ObjectInspectorProvider {
    override val engines: Set<DatasourceEngine> = setOf(DatasourceEngine.AEROSPIKE)

    override fun inspect(
        spec: ConnectionSpec,
        connection: Connection,
        objectRef: ObjectInspectorObjectRef,
    ): List<ObjectInspectorSection> {
        val namespace = objectRef.schema.trim()
        val setName = objectRef.name.trim()
        if (namespace.isBlank() || setName.isBlank()) {
            throw IllegalArgumentException("Schema (namespace) and set name are required.")
        }
        if (objectRef.type != InspectedObjectType.TABLE) {
            return listOf(
                ObjectInspectorSection(
                    id = "ddl",
                    title = "DDL",
                    status = ObjectInspectorSectionStatus.UNSUPPORTED,
                    message = "Aerospike inspector currently supports sets (tables) only.",
                ),
            )
        }

        val metadata = connection.metaData
        if (!tableExists(metadata, namespace, setName)) {
            throw InspectedObjectNotFoundException("Object $namespace.$setName not found.")
        }

        val ddlSection =
            buildSection(id = "ddl", title = "DDL") {
                val ddlText =
                    buildString {
                        appendLine("-- Aerospike is schemaless. A \"table\" maps to an Aerospike set.")
                        appendLine("-- Namespace: $namespace")
                        appendLine("-- Set: $setName")
                        appendLine("-- Primary key column: __key")
                        appendLine()
                        appendLine("-- Example: create a secondary index")
                        appendLine("CREATE INDEX ${setName.replace('-', '_')}_idx ON \"$setName\" (some_bin);")
                    }
                ObjectInspectorSection(
                    id = "ddl",
                    title = "DDL",
                    status = ObjectInspectorSectionStatus.OK,
                    kind = ObjectInspectorSectionKind.TEXT,
                    text = ddlText.trim(),
                )
            }

        val columnsSection =
            buildSection(id = "columns", title = "Columns") {
                val table = metadataColumns(metadata, namespace, setName)
                ObjectInspectorSection(
                    id = "columns",
                    title = "Columns",
                    status = ObjectInspectorSectionStatus.OK,
                    kind = ObjectInspectorSectionKind.TABLE,
                    table = table,
                )
            }

        val indexesSection =
            buildSection(id = "indexes", title = "Indexes") {
                val table = metadataIndexes(metadata, namespace, setName)
                ObjectInspectorSection(
                    id = "indexes",
                    title = "Indexes",
                    status = ObjectInspectorSectionStatus.OK,
                    kind = ObjectInspectorSectionKind.TABLE,
                    table = table,
                )
            }

        val constraintsSection =
            ObjectInspectorSection(
                id = "constraints",
                title = "Constraints",
                status = ObjectInspectorSectionStatus.OK,
                kind = ObjectInspectorSectionKind.KEY_VALUES,
                keyValues =
                    listOf(
                        ObjectInspectorKeyValue("supported", "false"),
                        ObjectInspectorKeyValue("note", "Aerospike does not enforce relational constraints."),
                    ),
            )

        val partitionsSection =
            ObjectInspectorSection(
                id = "partitions",
                title = "Partitions",
                status = ObjectInspectorSectionStatus.OK,
                kind = ObjectInspectorSectionKind.KEY_VALUES,
                keyValues =
                    listOf(
                        ObjectInspectorKeyValue("partitioned", "true"),
                        ObjectInspectorKeyValue("partitionCount", "4096"),
                    ),
            )

        val sizeSection =
            buildSection(id = "size", title = "Size & stats") {
                val columns = metadataColumns(metadata, namespace, setName).rows.size
                val indexes = metadataIndexes(metadata, namespace, setName).rows.size
                val keyValues =
                    listOfNotNull(
                        ObjectInspectorKeyValue("namespace", namespace),
                        ObjectInspectorKeyValue("set", setName),
                        ObjectInspectorKeyValue("columnCount", columns.toString()),
                        ObjectInspectorKeyValue("indexEntries", indexes.toString()),
                        metadata.databaseProductVersion?.takeIf { it.isNotBlank() }?.let { version ->
                            ObjectInspectorKeyValue("serverVersion", version)
                        },
                        metadata.driverVersion?.takeIf { it.isNotBlank() }?.let { version ->
                            ObjectInspectorKeyValue("jdbcDriverVersion", version)
                        },
                    )
                ObjectInspectorSection(
                    id = "size",
                    title = "Size & stats",
                    status = ObjectInspectorSectionStatus.OK,
                    kind = ObjectInspectorSectionKind.KEY_VALUES,
                    keyValues = keyValues,
                )
            }

        return listOf(
            ddlSection,
            columnsSection,
            indexesSection,
            constraintsSection,
            partitionsSection,
            sizeSection,
        )
    }

    private fun tableExists(
        metadata: DatabaseMetaData,
        namespace: String,
        setName: String,
    ): Boolean {
        val types = arrayOf("TABLE")
        val namespaceAsCatalog =
            metadata.getTables(namespace, null, setName, types).use { rs -> rs.next() }
        if (namespaceAsCatalog) {
            return true
        }

        return metadata.getTables(null, namespace, setName, types).use { rs -> rs.next() }
    }

    private fun metadataColumns(
        metadata: DatabaseMetaData,
        namespace: String,
        setName: String,
    ): ObjectInspectorTable {
        val cols = listOf("name", "type", "nullable")
        val rows =
            readColumns(
                metadata = metadata,
                catalog = namespace,
                schemaPattern = null,
                setName = setName,
            ).ifEmpty {
                readColumns(
                    metadata = metadata,
                    catalog = null,
                    schemaPattern = namespace,
                    setName = setName,
                )
            }.toMutableList()

        rows.sortWith(compareBy({ it[0].orEmpty().lowercase(Locale.getDefault()) }))

        return ObjectInspectorTable(
            columns = cols,
            rows = rows,
        )
    }

    private fun metadataIndexes(
        metadata: DatabaseMetaData,
        namespace: String,
        setName: String,
    ): ObjectInspectorTable {
        val columns = listOf("name", "column", "non_unique", "ordinal", "type", "direction")
        val rows =
            readIndexes(
                metadata = metadata,
                catalog = namespace,
                schemaPattern = null,
                setName = setName,
            ).ifEmpty {
                readIndexes(
                    metadata = metadata,
                    catalog = null,
                    schemaPattern = namespace,
                    setName = setName,
                )
            }

        return ObjectInspectorTable(
            columns = columns,
            rows = rows,
        )
    }

    private fun readColumns(
        metadata: DatabaseMetaData,
        catalog: String?,
        schemaPattern: String?,
        setName: String,
    ): List<List<String?>> {
        val rows = mutableListOf<List<String?>>()
        metadata.getColumns(catalog, schemaPattern, setName, "%").use { rs ->
            while (rs.next()) {
                val name = rs.getString("COLUMN_NAME") ?: rs.getString(4)
                val type = rs.getString("TYPE_NAME") ?: rs.getString(6) ?: "UNKNOWN"
                val nullable =
                    when (rs.getInt("NULLABLE")) {
                        DatabaseMetaData.columnNoNulls -> "NO"
                        DatabaseMetaData.columnNullable -> "YES"
                        else -> "UNKNOWN"
                    }
                rows.add(listOf(name, type, nullable))
            }
        }
        return rows
    }

    private fun readIndexes(
        metadata: DatabaseMetaData,
        catalog: String?,
        schemaPattern: String?,
        setName: String,
    ): List<List<String?>> {
        val rows = mutableListOf<List<String?>>()
        metadata.getIndexInfo(catalog, schemaPattern, setName, false, true).use { rs ->
            while (rs.next()) {
                val name = rs.getString("INDEX_NAME") ?: rs.getString(6)
                val column = rs.getString("COLUMN_NAME") ?: rs.getString(9)
                if (name.isNullOrBlank() || column.isNullOrBlank()) {
                    continue
                }
                val nonUnique = rs.getBoolean("NON_UNIQUE").toString()
                val ordinal = rs.getShort("ORDINAL_POSITION").toString()
                val type = rs.getShort("TYPE").toString()
                val direction = rs.getString("ASC_OR_DESC") ?: ""
                rows.add(listOf(name, column, nonUnique, ordinal, type, direction))
            }
        }
        return rows
    }

    private fun buildSection(
        id: String,
        title: String,
        block: () -> ObjectInspectorSection,
    ): ObjectInspectorSection =
        try {
            block()
        } catch (exception: SQLException) {
            if (isInsufficientPrivilege(exception)) {
                ObjectInspectorSection(
                    id = id,
                    title = title,
                    status = ObjectInspectorSectionStatus.INSUFFICIENT_PRIVILEGES,
                    message = "Connection does not have privileges to read ${title.lowercase()}.",
                )
            } else {
                ObjectInspectorSection(
                    id = id,
                    title = title,
                    status = ObjectInspectorSectionStatus.ERROR,
                    message = exception.message ?: "Unable to load ${title.lowercase()}.",
                )
            }
        }
}
