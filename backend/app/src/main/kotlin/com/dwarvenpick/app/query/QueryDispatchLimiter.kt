package com.dwarvenpick.app.query

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

internal class QueryDispatchLease(
    private val globalPermit: Semaphore,
    private val datasourcePermit: Semaphore,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) {
            globalPermit.release()
            datasourcePermit.release()
        }
    }
}

/**
 * Fair, process-local execution slots. Durable queue admission is coordinated separately in metadata Postgres.
 */
internal class QueryDispatchLimiter(
    maxRunningPerInstance: Int,
) {
    private val globalPermits = Semaphore(maxRunningPerInstance.coerceAtLeast(1), true)
    private val datasourcePermits = ConcurrentHashMap<String, Semaphore>()

    fun acquire(
        datasourceKey: String,
        maxRunningForDatasource: Int,
        timeout: Duration,
    ): QueryDispatchLease? {
        val datasourcePermit =
            datasourcePermits.computeIfAbsent(datasourceKey) {
                Semaphore(maxRunningForDatasource.coerceAtLeast(1), true)
            }
        val deadline = System.nanoTime() + timeout.coerceAtLeast(Duration.ZERO).toNanos()

        while (!Thread.currentThread().isInterrupted) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0 || !datasourcePermit.tryAcquire(remainingNanos, TimeUnit.NANOSECONDS)) {
                return null
            }
            if (globalPermits.tryAcquire()) {
                return QueryDispatchLease(globalPermits, datasourcePermit)
            }
            datasourcePermit.release()
            if (deadline - System.nanoTime() <= 0) {
                return null
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        return null
    }
}
