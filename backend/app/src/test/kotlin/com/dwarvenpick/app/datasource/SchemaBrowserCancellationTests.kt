package com.dwarvenpick.app.datasource

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SchemaBrowserCancellationTests {
    @Test
    fun `cancel before connection acquisition closes late connection without reading it`() {
        val connection = mock(Connection::class.java)
        val load = SchemaBrowserLoad()
        load.cancel()
        assertThatThrownBy { load.withConnection(handle(connection)) { error("must not read metadata") } }
            .isInstanceOf(CancellationException::class.java)
        verify(connection).close()
    }

    @Test
    fun `completed load returns its connection once and late cancel does not close it again`() {
        val connection = mock(Connection::class.java)
        val load = SchemaBrowserLoad()
        assertThat(load.withConnection(handle(connection)) { "done" }).isEqualTo("done")
        load.cancel()
        verify(connection).close()
    }

    @Test
    fun `disconnect stops blocked metadata and releases its connection`() {
        val fixture = fixture()
        val output =
            object : OutputStream() {
                private var writes = 0

                override fun write(value: Int) {
                    if (++writes > 1) throw IOException("browser disconnected")
                }
            }
        fixture.stream.use { stream ->
            assertThatThrownBy { stream.stream("trino", "reader", false).writeTo(output) }
                .isInstanceOf(IOException::class.java)
            assertThat(fixture.finished.await(5, TimeUnit.SECONDS)).isTrue()
            verify(fixture.connection).close()
        }
    }

    @Test
    fun `deadline stops metadata even when proxy keeps HTTP connection open`() {
        val fixture = fixture(maxLoadSeconds = 1)
        val output = ByteArrayOutputStream()
        fixture.stream.use { stream ->
            stream.stream("trino", "reader", false).writeTo(output)
            assertThat(fixture.finished.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(jacksonObjectMapper().readTree(output.toByteArray())["error"].asText()).contains("timed out")
            verify(fixture.connection).close()
        }
    }

    @Test
    fun `shutdown stops active metadata loads`() {
        val fixture = fixture()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val request = executor.submit { fixture.stream.stream("trino", "reader", false).writeTo(ByteArrayOutputStream()) }
            assertThat(fixture.started.await(5, TimeUnit.SECONDS)).isTrue()
            fixture.stream.close()
            request.get(5, TimeUnit.SECONDS)
            assertThat(fixture.finished.await(5, TimeUnit.SECONDS)).isTrue()
            verify(fixture.connection).close()
        } finally {
            executor.shutdownNow()
            fixture.stream.close()
        }
    }

    @Test
    fun `shutdown releases queued requests as well as running metadata`() {
        val fixture = fixture(expectedStarts = 4)
        val executor = Executors.newFixedThreadPool(5)
        val streamsStarted = CountDownLatch(5)
        try {
            val requests =
                (1..5).map {
                    executor.submit {
                        val output =
                            object : ByteArrayOutputStream() {
                                private var started = false

                                override fun flush() {
                                    if (!started) {
                                        started = true
                                        streamsStarted.countDown()
                                    }
                                }
                            }
                        fixture.stream.stream("trino", "reader", false).writeTo(output)
                    }
                }
            assertThat(streamsStarted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(fixture.started.await(5, TimeUnit.SECONDS)).isTrue()
            fixture.stream.close()
            requests.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            fixture.stream.close()
            executor.shutdownNow()
        }
    }

    private fun fixture(
        maxLoadSeconds: Long = 120,
        expectedStarts: Int = 1,
    ): Fixture {
        val connection = mock(Connection::class.java)
        val metadata = mock(DatabaseMetaData::class.java)
        val pool = mock(DatasourcePoolManager::class.java)
        val started = CountDownLatch(expectedStarts)
        val finished = CountDownLatch(1)
        `when`(pool.openConnection("trino", "reader")).thenReturn(handle(connection))
        `when`(connection.metaData).thenReturn(metadata)
        `when`(metadata.databaseProductName).thenReturn("Trino")
        `when`(metadata.schemas).thenAnswer {
            started.countDown()
            try {
                CountDownLatch(1).await()
                error("unexpected completion")
            } finally {
                finished.countDown()
            }
        }
        val properties = SchemaBrowserProperties(maxLoadSeconds = maxLoadSeconds)
        return Fixture(
            connection,
            SchemaBrowserStreamService(SchemaBrowserService(pool, properties), jacksonObjectMapper(), properties),
            started,
            finished,
        )
    }

    private fun handle(connection: Connection) = QueryConnectionHandle(mock(ConnectionSpec::class.java), connection)

    private data class Fixture(
        val connection: Connection,
        val stream: SchemaBrowserStreamService,
        val started: CountDownLatch,
        val finished: CountDownLatch,
    )
}
