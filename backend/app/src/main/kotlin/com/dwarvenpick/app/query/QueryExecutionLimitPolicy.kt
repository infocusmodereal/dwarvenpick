package com.dwarvenpick.app.query

import com.dwarvenpick.app.rbac.QueryAccessPolicy
import org.springframework.stereotype.Component

data class EffectiveQueryExecutionLimits(
    val maxRowsPerQuery: Int,
    val maxRuntimeSeconds: Int,
    val concurrencyLimit: Int,
)

@Component
class QueryExecutionLimitPolicy(
    private val queryExecutionProperties: QueryExecutionProperties,
) {
    fun resolve(policy: QueryAccessPolicy): EffectiveQueryExecutionLimits =
        EffectiveQueryExecutionLimits(
            maxRowsPerQuery =
                policy.maxRowsPerQuery
                    .coerceAtLeast(1)
                    .coerceAtMost(queryExecutionProperties.maxBufferedRows.coerceAtLeast(1)),
            maxRuntimeSeconds = policy.maxRuntimeSeconds.coerceAtLeast(1),
            concurrencyLimit =
                policy.concurrencyLimit
                    .coerceAtLeast(1)
                    .coerceAtMost(queryExecutionProperties.maxConcurrencyPerUser.coerceAtLeast(1)),
        )
}
