package com.dwarvenpick.app.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class QueryDispatchLimiterTests {
    @Test
    fun `datasource execution slots are bounded and released idempotently`() {
        val limiter = QueryDispatchLimiter(maxRunningPerInstance = 2)
        val first = limiter.acquire("hot", maxRunningForDatasource = 1, timeout = Duration.ofMillis(100))

        assertThat(first).isNotNull
        assertThat(limiter.acquire("hot", maxRunningForDatasource = 1, timeout = Duration.ofMillis(20))).isNull()

        first?.close()
        first?.close()
        val reacquired = limiter.acquire("hot", maxRunningForDatasource = 1, timeout = Duration.ofMillis(100))
        assertThat(reacquired).isNotNull
        reacquired?.close()
    }

    @Test
    fun `saturated datasource does not consume capacity for an independent datasource`() {
        val limiter = QueryDispatchLimiter(maxRunningPerInstance = 2)
        val hot = limiter.acquire("hot", maxRunningForDatasource = 1, timeout = Duration.ofMillis(100))
        val independent = limiter.acquire("independent", maxRunningForDatasource = 1, timeout = Duration.ofMillis(100))

        assertThat(hot).isNotNull
        assertThat(independent).isNotNull

        independent?.close()
        hot?.close()
    }

    @Test
    fun `aggregate execution slots remain fixed across datasources`() {
        val limiter = QueryDispatchLimiter(maxRunningPerInstance = 1)
        val first = limiter.acquire("one", maxRunningForDatasource = 1, timeout = Duration.ofMillis(100))

        assertThat(first).isNotNull
        assertThat(limiter.acquire("two", maxRunningForDatasource = 1, timeout = Duration.ofMillis(20))).isNull()

        first?.close()
    }
}
