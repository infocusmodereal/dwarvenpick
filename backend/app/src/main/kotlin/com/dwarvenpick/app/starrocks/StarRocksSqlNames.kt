package com.dwarvenpick.app.starrocks

import java.util.Locale

internal const val STARROCKS_DEFAULT_CATALOG = "default_catalog"

internal data class StarRocksSchemaRef(
    val catalog: String?,
    val database: String,
) {
    val displayName: String =
        if (catalog.isNullOrBlank() || isStarRocksDefaultCatalog(catalog)) {
            database
        } else {
            "$catalog.$database"
        }
}

internal fun isStarRocksDefaultCatalog(catalog: String): Boolean =
    catalog.trim().lowercase(Locale.getDefault()) == STARROCKS_DEFAULT_CATALOG

internal fun parseStarRocksExplorerSchema(schema: String): StarRocksSchemaRef {
    val trimmed = schema.trim()
    val separator = trimmed.indexOf('.')
    return if (separator <= 0 || separator == trimmed.lastIndex) {
        StarRocksSchemaRef(catalog = null, database = trimmed)
    } else {
        StarRocksSchemaRef(
            catalog = trimmed.substring(0, separator),
            database = trimmed.substring(separator + 1),
        )
    }
}

internal fun quoteStarRocksIdentifier(identifier: String): String = "`" + identifier.replace("`", "``") + "`"

internal fun buildStarRocksQualifiedName(parts: List<String>): String = parts.joinToString(".") { part -> quoteStarRocksIdentifier(part) }

internal fun StarRocksSchemaRef.qualifiedSchemaName(): String =
    if (catalog.isNullOrBlank() || isStarRocksDefaultCatalog(catalog)) {
        buildStarRocksQualifiedName(listOf(database))
    } else {
        buildStarRocksQualifiedName(listOf(catalog, database))
    }

internal fun StarRocksSchemaRef.qualifiedObjectName(name: String): String =
    if (catalog.isNullOrBlank() || isStarRocksDefaultCatalog(catalog)) {
        buildStarRocksQualifiedName(listOf(database, name))
    } else {
        buildStarRocksQualifiedName(listOf(catalog, database, name))
    }
