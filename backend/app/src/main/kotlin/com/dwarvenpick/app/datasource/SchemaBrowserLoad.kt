package com.dwarvenpick.app.datasource

import java.util.concurrent.CancellationException

/** Owns the borrowed connection until completion or HTTP disconnect, never both. */
class SchemaBrowserLoad {
    private var canceled = false
    private var handle: QueryConnectionHandle? = null

    @Synchronized
    fun checkActive() {
        if (canceled || Thread.currentThread().isInterrupted) {
            throw CancellationException("Schema browser load canceled.")
        }
    }

    fun <T> withConnection(
        connectionHandle: QueryConnectionHandle,
        block: () -> T,
    ): T {
        synchronized(this) {
            try {
                checkActive()
                handle = connectionHandle
            } catch (exception: CancellationException) {
                connectionHandle.close(evict = true)
                throw exception
            }
        }
        return try {
            block()
        } finally {
            val owned = synchronized(this) { handle.also { handle = null } }
            owned?.close()
        }
    }

    fun cancel() {
        val owned =
            synchronized(this) {
                canceled = true
                handle.also { handle = null }
            }
        // Eviction physically closes the JDBC connection, including Trino's internal
        // metadata statements, instead of returning an in-flight connection to the pool.
        owned?.close(evict = true)
    }
}
