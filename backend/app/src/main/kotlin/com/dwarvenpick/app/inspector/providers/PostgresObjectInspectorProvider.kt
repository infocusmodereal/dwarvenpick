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
class PostgresObjectInspectorProvider : ObjectInspectorProvider {
    override val engines: Set<DatasourceEngine> = setOf(DatasourceEngine.POSTGRESQL)

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

        val relation =
            lookupRelation(
                connection = connection,
                schema = schema,
                name = name,
                type = objectRef.type,
            ) ?: throw InspectedObjectNotFoundException("Object $schema.$name not found.")

        val quote = "\""
        val qualified = buildQualifiedName(quote, listOf(schema, name))

        val ddlSection =
            sectionOrError(
                id = "ddl",
                title = "DDL",
            ) {
                val ddlText =
                    when (objectRef.type) {
                        InspectedObjectType.VIEW -> buildViewDdl(connection, relation.oid, qualified)
                        InspectedObjectType.TABLE -> buildTableDdl(connection, relation.oid, qualified)
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
                val table =
                    runTableQuery(
                        connection,
                        """
                        SELECT a.attname AS name,
                               pg_catalog.format_type(a.atttypid, a.atttypmod) AS type,
                               CASE WHEN a.attnotnull THEN 'NO' ELSE 'YES' END AS nullable,
                               pg_get_expr(ad.adbin, ad.adrelid) AS default_value,
                               col_description(a.attrelid, a.attnum) AS comment
                        FROM pg_attribute a
                        LEFT JOIN pg_attrdef ad
                               ON a.attrelid = ad.adrelid AND a.attnum = ad.adnum
                        WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
                        ORDER BY a.attnum
                        """.trimIndent(),
                        bind = { stmt ->
                            stmt.setLong(1, relation.oid)
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

        val constraintsSection =
            sectionOrError(
                id = "constraints",
                title = "Constraints",
            ) {
                val table =
                    runTableQuery(
                        connection,
                        """
                        SELECT conname AS name,
                               contype AS type,
                               pg_get_constraintdef(oid, true) AS definition
                        FROM pg_constraint
                        WHERE conrelid = ?
                        ORDER BY contype, conname
                        """.trimIndent(),
                        bind = { stmt ->
                            stmt.setLong(1, relation.oid)
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

        val indexesSection =
            sectionOrError(
                id = "indexes",
                title = "Indexes",
            ) {
                val table =
                    runTableQuery(
                        connection,
                        """
                        SELECT i.relname AS name,
                               CASE WHEN idx.indisunique THEN 'YES' ELSE 'NO' END AS unique,
                               CASE WHEN idx.indisprimary THEN 'YES' ELSE 'NO' END AS primary,
                               pg_size_pretty(pg_relation_size(idx.indexrelid)) AS size,
                               pg_get_indexdef(idx.indexrelid) AS definition
                        FROM pg_index idx
                        JOIN pg_class i ON i.oid = idx.indexrelid
                        WHERE idx.indrelid = ?
                        ORDER BY idx.indisprimary DESC, idx.indisunique DESC, i.relname
                        """.trimIndent(),
                        bind = { stmt ->
                            stmt.setLong(1, relation.oid)
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

        val partitionsSection =
            sectionOrError(
                id = "partitions",
                title = "Partitions",
            ) {
                val partitionKey =
                    runCatching {
                        connection.prepareStatement("SELECT pg_get_partkeydef(?)").use { stmt ->
                            stmt.queryTimeout = 10
                            stmt.setLong(1, relation.oid)
                            stmt.executeQuery().use { rs ->
                                if (!rs.next()) {
                                    null
                                } else {
                                    rs.getString(1)
                                }
                            }
                        }
                    }.getOrNull()

                val table =
                    runTableQuery(
                        connection,
                        """
                        SELECT c.relname AS name,
                               pg_get_expr(c.relpartbound, c.oid) AS bound
                        FROM pg_inherits i
                        JOIN pg_class c ON c.oid = i.inhrelid
                        WHERE i.inhparent = ?
                        ORDER BY c.relname
                        """.trimIndent(),
                        bind = { stmt ->
                            stmt.setLong(1, relation.oid)
                        },
                    )

                if (partitionKey.isNullOrBlank() && table.rows.isEmpty()) {
                    ObjectInspectorSection(
                        id = "partitions",
                        title = "Partitions",
                        status = ObjectInspectorSectionStatus.OK,
                        kind = ObjectInspectorSectionKind.KEY_VALUES,
                        keyValues = listOf(ObjectInspectorKeyValue("partitioned", "false")),
                    )
                } else {
                    val summary =
                        buildList {
                            add(ObjectInspectorKeyValue("partitioned", "true"))
                            if (!partitionKey.isNullOrBlank()) {
                                add(ObjectInspectorKeyValue("partitionKey", partitionKey))
                            }
                            add(ObjectInspectorKeyValue("partitionCount", table.rows.size.toString()))
                        }

                    ObjectInspectorSection(
                        id = "partitions",
                        title = "Partitions",
                        status = ObjectInspectorSectionStatus.OK,
                        kind = ObjectInspectorSectionKind.TABLE,
                        message =
                            summary.joinToString("; ") { entry ->
                                "${entry.key}=${entry.value}"
                            },
                        table = table,
                    )
                }
            }

        val sizeSection =
            sectionOrError(
                id = "size",
                title = "Size",
            ) {
                val keyValues =
                    runKeyValueQuery(
                        connection,
                        """
                        SELECT pg_total_relation_size(?) AS total_bytes,
                               pg_relation_size(?) AS table_bytes,
                               pg_indexes_size(?) AS indexes_bytes
                        """.trimIndent(),
                        bind = { stmt ->
                            stmt.setLong(1, relation.oid)
                            stmt.setLong(2, relation.oid)
                            stmt.setLong(3, relation.oid)
                        },
                    )
                ObjectInspectorSection(
                    id = "size",
                    title = "Size",
                    status = ObjectInspectorSectionStatus.OK,
                    kind = ObjectInspectorSectionKind.KEY_VALUES,
                    keyValues = keyValues,
                )
            }

        val statsSection =
            sectionOrError(
                id = "stats",
                title = "Statistics",
            ) {
                val keyValues =
                    runKeyValueQuery(
                        connection,
                        """
                        SELECT n_live_tup,
                               n_dead_tup,
                               last_vacuum,
                               last_autovacuum,
                               last_analyze,
                               last_autoanalyze
                        FROM pg_stat_user_tables
                        WHERE relid = ?
                        """.trimIndent(),
                        bind = { stmt ->
                            stmt.setLong(1, relation.oid)
                        },
                    )
                ObjectInspectorSection(
                    id = "stats",
                    title = "Statistics",
                    status = ObjectInspectorSectionStatus.OK,
                    kind = ObjectInspectorSectionKind.KEY_VALUES,
                    keyValues = keyValues,
                )
            }

        return listOf(
            ddlSection,
            columnsSection,
            constraintsSection,
            indexesSection,
            partitionsSection,
            sizeSection,
            statsSection,
        )
    }

    private data class RelationLookup(
        val oid: Long,
    )

    private fun lookupRelation(
        connection: Connection,
        schema: String,
        name: String,
        type: InspectedObjectType,
    ): RelationLookup? {
        val relKinds =
            when (type) {
                InspectedObjectType.VIEW -> setOf("v", "m")
                InspectedObjectType.TABLE -> setOf("r", "p")
            }

        val placeholders = relKinds.joinToString(",") { "?" }
        val sql =
            """
            SELECT c.oid
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ($placeholders)
            """.trimIndent()

        return connection.prepareStatement(sql).use { stmt ->
            stmt.queryTimeout = 10
            stmt.setString(1, schema)
            stmt.setString(2, name)
            relKinds.forEachIndexed { index, kind ->
                stmt.setString(index + 3, kind)
            }
            stmt.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    RelationLookup(oid = rs.getLong(1))
                }
            }
        }
    }

    private fun buildViewDdl(
        connection: Connection,
        oid: Long,
        qualifiedName: String,
    ): String {
        val viewDef =
            connection
                .prepareStatement("SELECT pg_get_viewdef(?, true)")
                .use { stmt ->
                    stmt.queryTimeout = 10
                    stmt.setLong(1, oid)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) {
                            ""
                        } else {
                            rs.getString(1) ?: ""
                        }
                    }
                }.trim()
        return buildString {
            appendLine("-- Generated by dwarvenpick (best effort)")
            append("CREATE OR REPLACE VIEW ")
            append(qualifiedName)
            appendLine(" AS")
            append(viewDef.ifBlank { "-- view definition unavailable" })
            if (!viewDef.trimEnd().endsWith(";")) {
                appendLine(";")
            }
        }
    }

    private fun buildTableDdl(
        connection: Connection,
        oid: Long,
        qualifiedName: String,
    ): String {
        val columns =
            runTableQuery(
                connection,
                """
                SELECT a.attname AS name,
                       pg_catalog.format_type(a.atttypid, a.atttypmod) AS type,
                       CASE WHEN a.attnotnull THEN 'true' ELSE 'false' END AS not_null,
                       pg_get_expr(ad.adbin, ad.adrelid) AS default_value
                FROM pg_attribute a
                LEFT JOIN pg_attrdef ad
                       ON a.attrelid = ad.adrelid AND a.attnum = ad.adnum
                WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum
                """.trimIndent(),
                bind = { stmt -> stmt.setLong(1, oid) },
            )

        val constraints =
            runTableQuery(
                connection,
                """
                SELECT conname AS name,
                       pg_get_constraintdef(oid, true) AS definition
                FROM pg_constraint
                WHERE conrelid = ?
                ORDER BY contype, conname
                """.trimIndent(),
                bind = { stmt -> stmt.setLong(1, oid) },
            )

        val indexes =
            runTableQuery(
                connection,
                """
                SELECT pg_get_indexdef(idx.indexrelid) AS definition
                FROM pg_index idx
                WHERE idx.indrelid = ? AND NOT idx.indisprimary
                ORDER BY idx.indexrelid
                """.trimIndent(),
                bind = { stmt -> stmt.setLong(1, oid) },
            )

        return buildString {
            appendLine("-- Generated by dwarvenpick (best effort)")
            appendLine("CREATE TABLE $qualifiedName (")

            val lines = mutableListOf<String>()
            columns.rows.forEach { row ->
                val columnName = row.getOrNull(0) ?: return@forEach
                val type = row.getOrNull(1) ?: "unknown"
                val notNull = row.getOrNull(2)?.toBooleanStrictOrNull() ?: false
                val defaultValue = row.getOrNull(3)

                val line =
                    buildString {
                        append("    ")
                        append(quoteIdentifier("\"", columnName))
                        append(" ")
                        append(type)
                        if (!defaultValue.isNullOrBlank()) {
                            append(" DEFAULT ")
                            append(defaultValue)
                        }
                        if (notNull) {
                            append(" NOT NULL")
                        }
                    }
                lines.add(line)
            }

            constraints.rows.forEach { row ->
                val constraintName = row.getOrNull(0) ?: return@forEach
                val definition = row.getOrNull(1) ?: return@forEach
                lines.add("    CONSTRAINT ${quoteIdentifier("\"", constraintName)} $definition")
            }

            lines.forEachIndexed { index, line ->
                val suffix = if (index == lines.lastIndex) "" else ","
                appendLine("$line$suffix")
            }
            appendLine(");")

            if (indexes.rows.isNotEmpty()) {
                appendLine()
                appendLine("-- Indexes")
                indexes.rows.forEach { row ->
                    val indexDef = row.firstOrNull()?.trim().orEmpty()
                    if (indexDef.isNotBlank()) {
                        append(indexDef)
                        if (!indexDef.endsWith(";")) {
                            append(";")
                        }
                        appendLine()
                    }
                }
            }
        }
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
