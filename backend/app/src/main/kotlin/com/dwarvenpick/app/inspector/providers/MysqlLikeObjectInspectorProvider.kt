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
import com.dwarvenpick.app.systemhealth.isInsufficientPrivilege
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.SQLException

@Component
class MysqlLikeObjectInspectorProvider : ObjectInspectorProvider {
    override val engines: Set<DatasourceEngine> =
        setOf(
            DatasourceEngine.MYSQL,
            DatasourceEngine.MARIADB,
            DatasourceEngine.STARROCKS,
        )

    override fun inspect(
        spec: ConnectionSpec,
        connection: Connection,
        objectRef: ObjectInspectorObjectRef,
    ): List<ObjectInspectorSection> {
        val schema = objectRef.schema.trim()
        val name = objectRef.name.trim()
        if (schema.isBlank() || name.isBlank()) {
            throw IllegalArgumentException("Schema and object name are required.")
        }

        if (!objectExists(connection, schema, name, objectRef.type)) {
            throw InspectedObjectNotFoundException("Object $schema.$name not found.")
        }

        val quote = "`"
        val qualified = buildQualifiedName(quote, listOf(schema, name))

        val ddlSection =
            buildSection(
                id = "ddl",
                title = "DDL",
            ) {
                val sql =
                    when (objectRef.type) {
                        InspectedObjectType.VIEW -> "SHOW CREATE VIEW $qualified"
                        InspectedObjectType.TABLE -> "SHOW CREATE TABLE $qualified"
                    }
                val table = runTableQuery(connection, sql)
                val ddlText =
                    table.rows
                        .firstOrNull()
                        ?.getOrNull(1)
                        .orEmpty()
                        .trim()
                if (ddlText.isBlank()) {
                    throw SQLException("SHOW CREATE did not return DDL.")
                }
                ObjectInspectorSection(
                    id = "ddl",
                    title = "DDL",
                    status = ObjectInspectorSectionStatus.OK,
                    kind = ObjectInspectorSectionKind.TEXT,
                    text = ddlText,
                )
            }

        val columnsSection =
            buildSection(
                id = "columns",
                title = "Columns",
            ) {
                val table =
                    runTableQuery(
                        connection,
                        """
                        SELECT column_name AS name,
                               column_type AS type,
                               is_nullable AS nullable,
                               column_default AS default_value,
                               extra AS extra,
                               column_comment AS comment
                        FROM information_schema.columns
                        WHERE table_schema = ? AND table_name = ?
                        ORDER BY ordinal_position
                        """.trimIndent(),
                        bind = { stmt ->
                            stmt.setString(1, schema)
                            stmt.setString(2, name)
                        },
                    )
                ObjectInspectorSection(
                    id = "columns",
                    title = "Columns",
                    status = ObjectInspectorSectionStatus.OK,
                    kind = ObjectInspectorSectionKind.TABLE,
                    table = table,
                )
            }

        val indexesSection =
            buildSection(
                id = "indexes",
                title = "Indexes",
            ) {
                val table =
                    runTableQuery(
                        connection,
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
                        """.trimIndent(),
                        bind = { stmt ->
                            stmt.setString(1, schema)
                            stmt.setString(2, name)
                        },
                    )
                ObjectInspectorSection(
                    id = "indexes",
                    title = "Indexes",
                    status = ObjectInspectorSectionStatus.OK,
                    kind = ObjectInspectorSectionKind.TABLE,
                    table = table,
                )
            }

        val constraintsSection =
            buildSection(
                id = "constraints",
                title = "Constraints",
            ) {
                val table =
                    runTableQuery(
                        connection,
                        """
                        SELECT tc.constraint_name AS name,
                               tc.constraint_type AS type,
                               GROUP_CONCAT(kcu.column_name ORDER BY kcu.ordinal_position SEPARATOR ', ') AS columns,
                               rc.referenced_table_schema AS referenced_schema,
                               rc.referenced_table_name AS referenced_table
                        FROM information_schema.table_constraints tc
                        LEFT JOIN information_schema.key_column_usage kcu
                               ON tc.constraint_schema = kcu.constraint_schema
                              AND tc.table_schema = kcu.table_schema
                              AND tc.table_name = kcu.table_name
                              AND tc.constraint_name = kcu.constraint_name
                        LEFT JOIN information_schema.referential_constraints rc
                               ON tc.constraint_schema = rc.constraint_schema
                              AND tc.table_name = rc.table_name
                              AND tc.constraint_name = rc.constraint_name
                        WHERE tc.table_schema = ? AND tc.table_name = ?
                        GROUP BY tc.constraint_name, tc.constraint_type, rc.referenced_table_schema, rc.referenced_table_name
                        ORDER BY tc.constraint_type, tc.constraint_name
                        """.trimIndent(),
                        bind = { stmt ->
                            stmt.setString(1, schema)
                            stmt.setString(2, name)
                        },
                    )
                ObjectInspectorSection(
                    id = "constraints",
                    title = "Constraints",
                    status = ObjectInspectorSectionStatus.OK,
                    kind = ObjectInspectorSectionKind.TABLE,
                    table = table,
                )
            }

        val partitionsSection =
            buildSection(
                id = "partitions",
                title = "Partitions",
            ) {
                val table =
                    runTableQuery(
                        connection,
                        """
                        SELECT partition_name AS name,
                               partition_method AS method,
                               partition_expression AS expression,
                               subpartition_method AS sub_method,
                               subpartition_expression AS sub_expression,
                               partition_description AS description,
                               table_rows AS table_rows,
                               data_length AS data_length,
                               index_length AS index_length
                        FROM information_schema.partitions
                        WHERE table_schema = ? AND table_name = ? AND partition_name IS NOT NULL
                        ORDER BY partition_ordinal_position
                        """.trimIndent(),
                        bind = { stmt ->
                            stmt.setString(1, schema)
                            stmt.setString(2, name)
                        },
                    )

                if (table.rows.isEmpty()) {
                    ObjectInspectorSection(
                        id = "partitions",
                        title = "Partitions",
                        status = ObjectInspectorSectionStatus.OK,
                        kind = ObjectInspectorSectionKind.KEY_VALUES,
                        keyValues = listOf(ObjectInspectorKeyValue("partitioned", "false")),
                    )
                } else {
                    ObjectInspectorSection(
                        id = "partitions",
                        title = "Partitions",
                        status = ObjectInspectorSectionStatus.OK,
                        kind = ObjectInspectorSectionKind.TABLE,
                        table = table,
                    )
                }
            }

        val sizeSection =
            buildSection(
                id = "size",
                title = "Size & stats",
            ) {
                val keyValues =
                    runKeyValueQuery(
                        connection,
                        """
                        SELECT engine,
                               table_rows,
                               data_length,
                               index_length,
                               data_free,
                               create_time,
                               update_time
                        FROM information_schema.tables
                        WHERE table_schema = ? AND table_name = ?
                        """.trimIndent(),
                        bind = { stmt ->
                            stmt.setString(1, schema)
                            stmt.setString(2, name)
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

    private fun objectExists(
        connection: Connection,
        schema: String,
        name: String,
        type: InspectedObjectType,
    ): Boolean {
        val expectedTypes =
            when (type) {
                InspectedObjectType.VIEW -> setOf("VIEW")
                InspectedObjectType.TABLE -> setOf("TABLE", "BASE TABLE")
            }

        return connection.metaData
            .getTables(schema, null, name, expectedTypes.toTypedArray())
            .use { rs -> rs.next() }
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
