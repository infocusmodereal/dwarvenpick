package com.dwarvenpick.app.datasource

import com.aerospike.client.AerospikeClient
import com.aerospike.client.Info
import com.aerospike.client.policy.ClientPolicy
import com.dwarvenpick.app.datasource.aerospike.parseAerospikeJdbcTarget
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private data class SchemaCacheSnapshot(
    val response: DatasourceSchemaBrowserResponse,
    val fetchedAt: Instant,
)

@Service
class SchemaBrowserService(
    private val datasourcePoolManager: DatasourcePoolManager,
    private val schemaBrowserProperties: SchemaBrowserProperties,
) {
    private val cache = ConcurrentHashMap<String, SchemaCacheSnapshot>()

    fun fetchSchema(
        datasourceId: String,
        credentialProfile: String,
        refresh: Boolean,
    ): DatasourceSchemaBrowserResponse {
        val cacheKey = "$datasourceId::$credentialProfile"
        val now = Instant.now()
        val cachedSnapshot = cache[cacheKey]
        val cacheTtl = schemaBrowserProperties.cacheTtlSeconds.coerceAtLeast(1)

        if (!refresh && cachedSnapshot != null) {
            val ageSeconds = Duration.between(cachedSnapshot.fetchedAt, now).seconds
            if (ageSeconds <= cacheTtl) {
                return cachedSnapshot.response.copy(cached = true)
            }
        }

        val freshResponse = loadSchema(datasourceId, credentialProfile, now)
        cache[cacheKey] =
            SchemaCacheSnapshot(
                response = freshResponse,
                fetchedAt = now,
            )
        return freshResponse
    }

    private fun loadSchema(
        datasourceId: String,
        credentialProfile: String,
        fetchedAt: Instant,
    ): DatasourceSchemaBrowserResponse {
        val maxSchemas = schemaBrowserProperties.maxSchemas.coerceAtLeast(1)
        val maxTablesPerSchema = schemaBrowserProperties.maxTablesPerSchema.coerceAtLeast(1)
        val maxColumnsPerTable = schemaBrowserProperties.maxColumnsPerTable.coerceAtLeast(1)
        val handle =
            try {
                datasourcePoolManager.openConnection(
                    datasourceId = datasourceId,
                    credentialProfile = credentialProfile,
                )
            } catch (exception: RuntimeException) {
                throw SchemaBrowserUnavailableException(
                    message = "Unable to connect to datasource for schema browser.",
                    cause = exception,
                )
            }

        if (handle.spec.engine == DatasourceEngine.AEROSPIKE) {
            handle.connection.close()
            return loadAerospikeSchema(
                spec = handle.spec,
                datasourceId = datasourceId,
                fetchedAt = fetchedAt,
                maxSchemas = maxSchemas,
                maxTablesPerSchema = maxTablesPerSchema,
            )
        }

        return try {
            handle.connection.use { connection ->
                val metadata = connection.metaData
                val catalog = connection.catalog
                val schemaNames = mutableListOf<String>()
                var schemaUsesCatalogFallback = false
                val productName = metadata.databaseProductName.lowercase(Locale.getDefault())
                val useCatalogsAsSchemas =
                    productName.contains("mysql") ||
                        productName.contains("mariadb") ||
                        productName.contains("aerospike")

                if (useCatalogsAsSchemas) {
                    metadata.catalogs.use { catalogs ->
                        while (catalogs.next() && schemaNames.size < maxSchemas) {
                            val schemaName =
                                catalogs.getString("TABLE_CAT")
                                    ?: catalogs.getString(1)
                                    ?: continue
                            if (schemaName.isNotBlank()) {
                                schemaNames.add(schemaName)
                            }
                        }
                    }
                    schemaUsesCatalogFallback = true
                } else {
                    metadata.schemas.use { schemas ->
                        while (schemas.next() && schemaNames.size < maxSchemas) {
                            val schemaName =
                                schemas.getString("TABLE_SCHEM")
                                    ?: schemas.getString(1)
                                    ?: continue
                            if (schemaName.isNotBlank()) {
                                schemaNames.add(schemaName)
                            }
                        }
                    }
                }

                if (schemaNames.isEmpty()) {
                    if (!catalog.isNullOrBlank()) {
                        schemaNames.add(catalog)
                        schemaUsesCatalogFallback = true
                    } else {
                        schemaNames.add("default")
                    }
                }

                val schemaResponses =
                    schemaNames
                        .distinctBy { schema -> schema.lowercase(Locale.getDefault()) }
                        .sortedBy { schema -> schema.lowercase(Locale.getDefault()) }
                        .map { schemaName ->
                            val resolvedCatalog = if (schemaUsesCatalogFallback) schemaName else catalog
                            val resolvedSchemaPattern = if (schemaUsesCatalogFallback) null else schemaName
                            val tables = mutableListOf<DatasourceTableEntryResponse>()
                            metadata
                                .getTables(
                                    resolvedCatalog,
                                    resolvedSchemaPattern,
                                    "%",
                                    arrayOf("TABLE", "VIEW"),
                                ).use { tableRs ->
                                    while (tableRs.next() && tables.size < maxTablesPerSchema) {
                                        val tableName =
                                            tableRs.getString("TABLE_NAME")
                                                ?: tableRs.getString(3)
                                                ?: continue
                                        val tableType = tableRs.getString("TABLE_TYPE") ?: "TABLE"

                                        val columns = mutableListOf<DatasourceColumnEntryResponse>()
                                        metadata
                                            .getColumns(
                                                resolvedCatalog,
                                                resolvedSchemaPattern,
                                                tableName,
                                                "%",
                                            ).use { columnRs ->
                                                while (columnRs.next() && columns.size < maxColumnsPerTable) {
                                                    val columnName =
                                                        columnRs.getString("COLUMN_NAME")
                                                            ?: columnRs.getString(4)
                                                            ?: continue
                                                    val jdbcType = columnRs.getString("TYPE_NAME") ?: "UNKNOWN"
                                                    val nullableCode =
                                                        columnRs.getInt("NULLABLE")
                                                    columns.add(
                                                        DatasourceColumnEntryResponse(
                                                            name = columnName,
                                                            jdbcType = jdbcType,
                                                            nullable = nullableCode != 0,
                                                        ),
                                                    )
                                                }
                                            }

                                        tables.add(
                                            DatasourceTableEntryResponse(
                                                table = tableName,
                                                type = tableType,
                                                columns = columns,
                                            ),
                                        )
                                    }
                                }

                            DatasourceSchemaEntryResponse(
                                schema = schemaName,
                                tables = tables.sortedBy { table -> table.table.lowercase(Locale.getDefault()) },
                            )
                        }

                DatasourceSchemaBrowserResponse(
                    datasourceId = datasourceId,
                    cached = false,
                    fetchedAt = fetchedAt.toString(),
                    schemas = schemaResponses,
                )
            }
        } catch (exception: RuntimeException) {
            throw SchemaBrowserUnavailableException(
                message = "Unable to load schema metadata from datasource.",
                cause = exception,
            )
        }
    }

    private fun loadAerospikeSchema(
        spec: ConnectionSpec,
        datasourceId: String,
        fetchedAt: Instant,
        maxSchemas: Int,
        maxTablesPerSchema: Int,
    ): DatasourceSchemaBrowserResponse {
        val target = parseAerospikeJdbcTarget(spec.jdbcUrl)
        val policy =
            ClientPolicy().apply {
                if (spec.username.isNotBlank()) {
                    user = spec.username
                }
                if (spec.password.isNotBlank()) {
                    password = spec.password
                }
                loginTimeout = 3_000
                timeout = 1_000
            }

        return try {
            AerospikeClient(policy, target.host, target.port).use { client ->
                val node =
                    client.nodes.firstOrNull()
                        ?: throw SchemaBrowserUnavailableException("Unable to connect to Aerospike cluster.")

                val namespaces =
                    parseAerospikeList(Info.request(node, "namespaces"))
                        .ifEmpty {
                            target.namespace?.let { listOf(it) } ?: emptyList()
                        }.ifEmpty { listOf("default") }
                        .distinct()
                        .take(maxSchemas)

                val setsByNamespace =
                    runCatching { Info.request(node, "sets") }
                        .map { info -> parseAerospikeSets(info) }
                        .getOrDefault(emptyMap())

                val schemaResponses =
                    namespaces
                        .sortedBy { namespace -> namespace.lowercase(Locale.getDefault()) }
                        .map { namespace ->
                            val tables =
                                setsByNamespace[namespace]
                                    .orEmpty()
                                    .sortedBy { name -> name.lowercase(Locale.getDefault()) }
                                    .take(maxTablesPerSchema)
                                    .map { setName ->
                                        DatasourceTableEntryResponse(
                                            table = setName,
                                            type = "TABLE",
                                            columns = emptyList(),
                                        )
                                    }

                            DatasourceSchemaEntryResponse(
                                schema = namespace,
                                tables = tables,
                            )
                        }

                DatasourceSchemaBrowserResponse(
                    datasourceId = datasourceId,
                    cached = false,
                    fetchedAt = fetchedAt.toString(),
                    schemas = schemaResponses,
                )
            }
        } catch (exception: SchemaBrowserUnavailableException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw SchemaBrowserUnavailableException(
                message = "Unable to load Aerospike schema metadata.",
                cause = exception,
            )
        }
    }

    private fun parseAerospikeList(value: String?): List<String> =
        value
            ?.split(';')
            ?.map { entry -> entry.trim() }
            ?.filter { entry -> entry.isNotBlank() }
            ?: emptyList()

    private fun parseAerospikeSets(info: String): Map<String, Set<String>> {
        val pattern = Regex("ns=([^:;\\s]+):set=([^:;\\s]+)")
        val setsByNamespace = mutableMapOf<String, MutableSet<String>>()
        pattern.findAll(info).forEach { match ->
            val namespace =
                match.groupValues
                    .getOrNull(1)
                    ?.trim()
                    .orEmpty()
            val setName =
                match.groupValues
                    .getOrNull(2)
                    ?.trim()
                    .orEmpty()
            if (namespace.isBlank() || setName.isBlank()) {
                return@forEach
            }
            setsByNamespace.getOrPut(namespace) { mutableSetOf() }.add(setName)
        }
        return setsByNamespace
    }
}
