package com.dwarvenpick.app.datasource.aerospike

data class AerospikeJdbcTarget(
    val host: String,
    val port: Int,
    val namespace: String?,
)

fun parseAerospikeJdbcTarget(jdbcUrl: String): AerospikeJdbcTarget {
    val pattern = Regex("^jdbc:aerospike:([^:/?#]+)(?::(\\d+))?(?:/([^?]+))?.*", RegexOption.IGNORE_CASE)
    val match =
        pattern.find(jdbcUrl.trim())
            ?: throw IllegalArgumentException("Unsupported Aerospike JDBC URL.")

    val host = match.groupValues[1]
    val port =
        match.groupValues
            .getOrNull(2)
            ?.takeIf { value -> value.isNotBlank() }
            ?.toInt() ?: 3000
    val namespace = match.groupValues.getOrNull(3)?.takeIf { value -> value.isNotBlank() }
    return AerospikeJdbcTarget(
        host = host,
        port = port,
        namespace = namespace,
    )
}
