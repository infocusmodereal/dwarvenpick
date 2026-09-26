package com.dwarvenpick.app.datasource

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
class TrinoSchemaBrowserContainerTests {
    @Test
    fun `catalog scoped batches preserve TPCH metadata with fewer coordinator queries`() {
        val properties = SchemaBrowserProperties()
        val legacyQueries = mutableListOf<String>()
        val optimizedQueries = mutableListOf<String>()
        val legacy = service("tpch", DatasourceEngine.POSTGRESQL, properties, legacyQueries)
        val optimized = service("tpch", DatasourceEngine.TRINO, properties, optimizedQueries)
        // Warm both paths, then alternate order. These measurements always bypass
        // the application cache; query counts and content equality are the assertions.
        legacy.fetchSchema("trino", "reader", true)
        optimized.fetchSchema("trino", "reader", true)
        val legacyTimes = mutableListOf<Long>()
        val optimizedTimes = mutableListOf<Long>()
        repeat(3) { round ->
            legacyQueries.clear()
            optimizedQueries.clear()
            lateinit var before: DatasourceSchemaBrowserResponse
            lateinit var after: DatasourceSchemaBrowserResponse

            fun measureLegacy() {
                val started = System.nanoTime()
                before = legacy.fetchSchema("trino", "reader", true)
                legacyTimes.add((System.nanoTime() - started) / 1_000_000)
            }

            fun measureOptimized() {
                val started = System.nanoTime()
                after = optimized.fetchSchema("trino", "reader", true)
                optimizedTimes.add((System.nanoTime() - started) / 1_000_000)
            }
            if (round % 2 == 0) {
                measureLegacy()
                measureOptimized()
            } else {
                measureOptimized()
                measureLegacy()
            }
            val catalogSchemas =
                connection("tpch").use { connection ->
                    connection.metaData.getSchemas("tpch", null).use { rows ->
                        buildSet { while (rows.next()) add(rows.getString("TABLE_SCHEM")) }
                    }
                }
            assertThat(after.schemas).containsExactlyElementsOf(before.schemas.filter { it.schema in catalogSchemas })
            assertThat(after.schemas.map { it.schema }).containsExactlyInAnyOrderElementsOf(catalogSchemas)
            assertThat(optimizedQueries.size).isLessThan(legacyQueries.size)
            assertThat(optimizedQueries.size).isEqualTo(1 + after.schemas.size + after.schemas.count { it.tables.isNotEmpty() })
            val tableCount = after.schemas.sumOf { it.tables.size }
            val columnCount = after.schemas.sumOf { schema -> schema.tables.sumOf { it.columns.size } }
            println(
                "TRINO_METADATA round=$round legacyMs=${legacyTimes.last()} optimizedMs=${optimizedTimes.last()} " +
                    "legacyQueries=${legacyQueries.size} optimizedQueries=${optimizedQueries.size} " +
                    "schemas=${after.schemas.size} tables=$tableCount columns=$columnCount",
            )
        }
        val queriesBeforeCacheHit = optimizedQueries.size
        assertThat(optimized.fetchSchema("trino", "reader", false).cached).isTrue()
        assertThat(optimizedQueries.size).isEqualTo(queriesBeforeCacheHit)
        println("TRINO_METADATA medianLegacyMs=${legacyTimes.sorted()[1]} medianOptimizedMs=${optimizedTimes.sorted()[1]}")
    }

    @Test
    fun `column batches respect table and column limits without wildcard name matching`() {
        val schema = "a_metadata_${UUID.randomUUID().toString().replace("-", "")}"
        connection("memory").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA memory.$schema")
                val names = (0..33).map { "table_${it.toString().padStart(2, '0')}" } + listOf("wild_%", "wild_x", "quote'and\"name")
                try {
                    names.forEach { name ->
                        val quoted = "\"${name.replace("\"", "\"\"")}\""
                        statement.execute("CREATE TABLE memory.$schema.$quoted (id bigint, payload array(varchar), amount decimal(12, 2))")
                    }
                    statement.execute("CREATE VIEW memory.$schema.metadata_view AS SELECT * FROM memory.$schema.table_00")
                    val queries = mutableListOf<String>()
                    val all =
                        service(
                            "memory",
                            DatasourceEngine.TRINO,
                            SchemaBrowserProperties(maxSchemas = 1),
                            queries,
                        ).fetchSchema("trino", "reader", true)
                    assertThat(all.schemas).hasSize(1)
                    assertThat(all.schemas.single().schema).isEqualTo(schema)
                    assertThat(
                        all.schemas
                            .single()
                            .tables
                            .map { it.table },
                    ).containsExactlyInAnyOrderElementsOf(names + "metadata_view")
                    assertThat(all.schemas.single().tables).allSatisfy { table ->
                        assertThat(table.columns.map { it.name }).containsExactly("id", "payload", "amount")
                        assertThat(table.columns.map { it.jdbcType }).containsExactly("bigint", "array(varchar)", "decimal(12,2)")
                    }
                    assertThat(
                        all.schemas
                            .single()
                            .tables
                            .single { it.table == "metadata_view" }
                            .type,
                    ).isEqualTo("VIEW")
                    assertThat(queries).hasSize(4) // schemas + tables + two bounded column batches
                    queries.clear()
                    val limited =
                        service(
                            "memory",
                            DatasourceEngine.TRINO,
                            SchemaBrowserProperties(maxSchemas = 1, maxTablesPerSchema = 3, maxColumnsPerTable = 2),
                            queries,
                        ).fetchSchema("trino", "reader", true)
                    assertThat(limited.schemas.single().tables).hasSize(3).allSatisfy { table -> assertThat(table.columns).hasSize(2) }
                    assertThat(queries).hasSize(3)
                } finally {
                    statement.execute("DROP VIEW IF EXISTS memory.$schema.metadata_view")
                    names.forEach { name -> statement.execute("DROP TABLE IF EXISTS memory.$schema.\"${name.replace("\"", "\"\"")}\"") }
                    statement.execute("DROP SCHEMA memory.$schema")
                }
            }
        }
    }

    private fun service(
        catalog: String,
        engine: DatasourceEngine,
        properties: SchemaBrowserProperties,
        queries: MutableList<String>,
    ): SchemaBrowserService {
        val pools = mock(DatasourcePoolManager::class.java)
        val spec = mock(ConnectionSpec::class.java)
        `when`(spec.engine).thenReturn(engine)
        `when`(pools.openConnection("trino", "reader")).thenAnswer {
            val raw = connection(catalog)
            val proxy =
                Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                    try {
                        when (method.name) {
                            "getMetaData" -> {
                                val metadata = raw.metaData
                                Proxy.newProxyInstance(
                                    DatabaseMetaData::class.java.classLoader,
                                    arrayOf(DatabaseMetaData::class.java),
                                ) { _, metadataMethod, metadataArgs ->
                                    try {
                                        if (metadataMethod.name in
                                            setOf("getSchemas", "getTables", "getColumns")
                                        ) {
                                            queries.add(metadataMethod.name)
                                        }
                                        metadataMethod.invoke(metadata, *(metadataArgs ?: emptyArray()))
                                    } catch (exception: InvocationTargetException) {
                                        throw exception.targetException
                                    }
                                }
                            }
                            else -> {
                                if (method.name == "prepareStatement") queries.add(args!![0].toString())
                                method.invoke(raw, *(args ?: emptyArray()))
                            }
                        }
                    } catch (exception: InvocationTargetException) {
                        throw exception.targetException
                    }
                } as Connection
            QueryConnectionHandle(spec, proxy)
        }
        return SchemaBrowserService(pools, properties)
    }

    private fun connection(catalog: String) =
        DriverManager.getConnection("jdbc:trino://${trino.host}:${trino.getMappedPort(8080)}/$catalog", "metadata-performance-test", null)

    companion object {
        @JvmStatic
        @Container
        val trino =
            GenericContainer<Nothing>("trinodb/trino:479").apply {
                withExposedPorts(8080)
                waitingFor(Wait.forLogMessage(".*======== SERVER STARTED ========.*\\n", 1).withStartupTimeout(Duration.ofSeconds(120)))
            }
    }
}
