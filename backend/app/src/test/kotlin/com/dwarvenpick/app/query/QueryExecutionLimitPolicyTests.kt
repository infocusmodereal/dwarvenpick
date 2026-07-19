package com.dwarvenpick.app.query

import com.dwarvenpick.app.rbac.QueryAccessPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QueryExecutionLimitPolicyTests {
    private val limitPolicy =
        QueryExecutionLimitPolicy(
            QueryExecutionProperties(
                maxBufferedRows = 5000,
                maxConcurrencyPerUser = 3,
            ),
        )

    @Test
    fun `global ceilings cap RBAC unlimited rows and concurrency`() {
        val limits =
            limitPolicy.resolve(
                QueryAccessPolicy(
                    credentialProfile = "read-write",
                    readOnly = false,
                    maxRowsPerQuery = Int.MAX_VALUE,
                    maxRuntimeSeconds = Int.MAX_VALUE,
                    concurrencyLimit = Int.MAX_VALUE,
                ),
            )

        assertThat(limits.maxRowsPerQuery).isEqualTo(5000)
        assertThat(limits.maxRuntimeSeconds).isEqualTo(Int.MAX_VALUE)
        assertThat(limits.concurrencyLimit).isEqualTo(3)
    }

    @Test
    fun `stricter RBAC limits remain unchanged`() {
        val limits =
            limitPolicy.resolve(
                QueryAccessPolicy(
                    credentialProfile = "read-only",
                    readOnly = true,
                    maxRowsPerQuery = 100,
                    maxRuntimeSeconds = 30,
                    concurrencyLimit = 1,
                ),
            )

        assertThat(limits.maxRowsPerQuery).isEqualTo(100)
        assertThat(limits.maxRuntimeSeconds).isEqualTo(30)
        assertThat(limits.concurrencyLimit).isEqualTo(1)
    }
}
