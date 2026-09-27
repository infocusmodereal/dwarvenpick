package com.dwarvenpick.app.datasource

import com.dwarvenpick.app.auth.ErrorResponse
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Service
class SchemaBrowserStreamService(
    private val schemaBrowserService: SchemaBrowserService,
    private val objectMapper: ObjectMapper,
    private val properties: SchemaBrowserProperties,
) : AutoCloseable {
    private val activeLoads = ConcurrentHashMap.newKeySet<SchemaBrowserLoad>()

    private val workers =
        ThreadPoolExecutor(
            4,
            4,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(16),
            { task -> Thread(task, "schema-browser").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )

    fun stream(
        datasourceId: String,
        credentialProfile: String,
        refresh: Boolean,
    ): StreamingResponseBody =
        StreamingResponseBody { output ->
            val load = SchemaBrowserLoad()
            activeLoads.add(load)
            var future: Future<DatasourceSchemaBrowserResponse>? = null
            try {
                // Whitespace is valid before a JSON document. Flushing it periodically
                // detects a disconnected browser without a separate cancellation route
                // (which could land on a different replica).
                output.write(' '.code)
                output.flush()
                future =
                    workers.submit<DatasourceSchemaBrowserResponse> {
                        schemaBrowserService.fetchSchema(datasourceId, credentialProfile, refresh, load)
                    }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.maxLoadSeconds.coerceAtLeast(1))
                while (true) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) throw TimeoutException("Schema browser load timed out.")
                    val result =
                        try {
                            future.get(minOf(remaining, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS)
                        } catch (_: TimeoutException) {
                            output.write(' '.code)
                            output.flush()
                            continue
                        }
                    output.write(objectMapper.writeValueAsBytes(result))
                    output.flush()
                    break
                }
            } catch (exception: IOException) {
                throw exception
            } catch (exception: Exception) {
                val message =
                    if (exception is TimeoutException) {
                        "Schema browser load timed out. Try again or select another connection."
                    } else {
                        "Unable to load schema browser data right now."
                    }
                output.write(objectMapper.writeValueAsBytes(ErrorResponse(message)))
                output.flush()
            } finally {
                // Interrupt pool acquisition as well as canceling an already borrowed connection.
                runCatching { load.cancel() }
                future?.cancel(true)
                activeLoads.remove(load)
                workers.purge()
            }
        }

    @PreDestroy
    override fun close() {
        activeLoads.forEach { load -> runCatching { load.cancel() } }
        workers.shutdownNow().filterIsInstance<Future<*>>().forEach { it.cancel(true) }
    }
}
