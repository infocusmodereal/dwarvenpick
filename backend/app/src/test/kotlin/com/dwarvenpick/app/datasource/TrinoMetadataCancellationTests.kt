package com.dwarvenpick.app.datasource

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs the actual Trino JDBC driver and Hikari pool against a local Trino protocol fixture. */
class TrinoMetadataCancellationTests {
    @Test
    fun `HTTP disconnect cancels Trino metadata on coordinator and evicts pooled connection`() {
        val canceled = CountDownLatch(1)
        val queried = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            if (exchange.requestMethod == "DELETE") {
                canceled.countDown()
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            } else {
                queried.countDown()
                val payload =
                    """
                    {"id":"metadata-test","infoUri":"$baseUrl/info","nextUri":"$baseUrl/v1/statement/next",
                     "stats":{"state":"RUNNING","queued":false,"scheduled":true,"nodes":1,
                     "totalSplits":1,"queuedSplits":0,"runningSplits":1,"completedSplits":0,
                     "cpuTimeMillis":0,"wallTimeMillis":0,"queuedTimeMillis":0,"elapsedTimeMillis":0,
                     "processedRows":0,"processedBytes":0,"physicalInputBytes":0,"peakMemoryBytes":0,
                     "spilledBytes":0},"warnings":[]}
                    """.trimIndent().toByteArray()
                if (exchange.requestMethod == "GET") canceled.await(50, TimeUnit.MILLISECONDS)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        }
        server.start()
        try {
            val config =
                HikariConfig().apply {
                    jdbcUrl = "jdbc:trino://127.0.0.1:${server.address.port}/memory"
                    username = "metadata-test"
                    driverClassName = "io.trino.jdbc.TrinoDriver"
                    maximumPoolSize = 1
                    minimumIdle = 0
                    initializationFailTimeout = -1
                }
            HikariDataSource(config).use { hikari ->
                val spec = mock(ConnectionSpec::class.java)
                `when`(spec.engine).thenReturn(DatasourceEngine.TRINO)
                val handle = QueryConnectionHandle(spec, hikari.connection).apply { pool = hikari }
                val pools = mock(DatasourcePoolManager::class.java)
                `when`(pools.openConnection("trino", "reader")).thenReturn(handle)
                val properties = SchemaBrowserProperties(maxLoadSeconds = 10)
                SchemaBrowserStreamService(SchemaBrowserService(pools, properties), jacksonObjectMapper(), properties).use { service ->
                    val output =
                        object : OutputStream() {
                            var writes = 0

                            override fun write(value: Int) {
                                if (++writes > 1) {
                                    assertThat(queried.await(5, TimeUnit.SECONDS)).isTrue()
                                    throw IOException("browser switched connection")
                                }
                            }
                        }
                    assertThatThrownBy { service.stream("trino", "reader", false).writeTo(output) }
                        .isInstanceOf(IOException::class.java)
                    assertThat(canceled.await(5, TimeUnit.SECONDS)).describedAs("Trino coordinator received DELETE").isTrue()
                    assertThat(hikari.hikariPoolMXBean.activeConnections).isZero()
                    assertThat(handle.connection.isClosed).isTrue()
                }
            }
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
