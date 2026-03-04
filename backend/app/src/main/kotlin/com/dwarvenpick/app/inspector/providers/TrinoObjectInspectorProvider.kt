package com.dwarvenpick.app.inspector.providers

import com.dwarvenpick.app.datasource.ConnectionSpec
import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.inspector.InspectedObjectNotFoundException
import com.dwarvenpick.app.inspector.InspectedObjectType
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
class TrinoObjectInspectorProvider : ObjectInspectorProvider {
    override val engines: Set<DatasourceEngine> = setOf(DatasourceEngine.TRINO)

    override fun inspect(
        spec: ConnectionSpec,
        connection: Connection,
        objectRef: ObjectInspectorObjectRef,
    ): List<ObjectInspectorSection> {
        val schema = objectRef.schema.trim()
        val name = objectRef.name.trim()
        if (name.isBlank()) {
            throw IllegalArgumentException("Object name is required.")
        }

        val quote = "\""
        val catalog = connection.catalog?.trim().orEmpty()
        val qualifiedParts =
            buildList {
                if (catalog.isNotBlank()) {
                    add(catalog)
                }
                if (schema.isNotBlank()) {
                    add(schema)
                }
                add(name)
            }
        val qualified = buildQualifiedName(quote, qualifiedParts)

        if (!objectExists(connection, schema, name, objectRef.type)) {
            throw InspectedObjectNotFoundException("Object ${qualifiedParts.joinToString(".")} not found.")
        }

        val ddlSection =
            sectionOrError(
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
                        ?.firstOrNull()
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
            sectionOrError(
                id = "columns",
                title = "Columns",
            ) {
                val sql =
                    if (catalog.isBlank()) {
                        """
                        SELECT column_name AS name,
                               data_type AS type,
                               is_nullable AS nullable,
                               column_default AS default_value
                        FROM information_schema.columns
                        WHERE table_schema = ? AND table_name = ?
                        ORDER BY ordinal_position
                        """.trimIndent()
                    } else {
                        """
                        SELECT column_name AS name,
                               data_type AS type,
                               is_nullable AS nullable,
                               column_default AS default_value
                        FROM information_schema.columns
                        WHERE table_catalog = ? AND table_schema = ? AND table_name = ?
                        ORDER BY ordinal_position
                        """.trimIndent()
                    }

                val table =
                    runTableQuery(
                        connection,
                        sql,
                        bind = { stmt ->
                            if (catalog.isBlank()) {
                                stmt.setString(1, schema)
                                stmt.setString(2, name)
                            } else {
                                stmt.setString(1, catalog)
                                stmt.setString(2, schema)
                                stmt.setString(3, name)
                            }
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

        val statsSection =
            sectionOrError(
                id = "stats",
                title = "Statistics",
            ) {
                val table =
                    runTableQuery(
                        connection,
                        "SHOW STATS FOR $qualified",
                    )
                ObjectInspectorSection(
                    id = "stats",
                    title = "Statistics",
                    status = ObjectInspectorSectionStatus.OK,
                    kind = ObjectInspectorSectionKind.TABLE,
                    table = table,
                )
            }

        val partitionsSection =
            ObjectInspectorSection(
                id = "partitions",
                title = "Partitions",
                status = ObjectInspectorSectionStatus.UNSUPPORTED,
                message = "Partition inspection is connector-specific for Trino and is not implemented yet.",
            )

        val sizeSection =
            ObjectInspectorSection(
                id = "size",
                title = "Size",
                status = ObjectInspectorSectionStatus.UNSUPPORTED,
                message = "Size inspection is connector-specific for Trino and is not implemented yet.",
            )

        return listOf(
            ddlSection,
            columnsSection,
            statsSection,
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
                InspectedObjectType.TABLE -> setOf("TABLE")
            }

        return connection.metaData
            .getTables(connection.catalog, schema.ifBlank { null }, name, expectedTypes.toTypedArray())
            .use { rs -> rs.next() }
    }

    private fun sectionOrError(
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
