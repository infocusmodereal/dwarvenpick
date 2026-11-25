package com.dwarvenpick.app.query

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

class QueryIdempotencyConflictException(
    message: String = "Idempotency-Key is already bound to a different query request.",
) : RuntimeException(message)

internal object QuerySubmissionIdentity {
    fun executionId(
        key: String?,
        actorId: String?,
    ): String {
        if (key == null) return UUID.randomUUID().toString()
        require(key.length in 1..128 && key.all { it in '!'..'~' }) {
            "Idempotency-Key must contain 1 to 128 visible ASCII characters."
        }
        requireNotNull(actorId) { "Idempotency-Key requires a persistent user identity." }
        val fingerprint = sha256Hex("query-idempotency:$actorId:$key")
        return UUID(
            fingerprint.take(16).toULong(16).toLong(),
            fingerprint.substring(16, 32).toULong(16).toLong(),
        ).toString()
    }

    fun fingerprint(record: PersistedQueryRuntimeRecord): String =
        fingerprint(
            record.actor,
            record.datasourceId,
            record.credentialProfile,
            record.queryHash,
            record.defaultSchema,
            record.justification,
            record.scriptStopOnError.toString(),
            record.scriptTransactionMode.name,
        )

    fun fingerprint(record: PersistedQueryRuntimeMetadataRecord): String =
        fingerprint(
            record.actor,
            record.datasourceId,
            record.credentialProfile,
            record.queryHash,
            record.defaultSchema,
            record.justification,
            record.scriptStopOnError.toString(),
            record.scriptTransactionMode.name,
        )

    private fun fingerprint(vararg fields: String?): String =
        sha256Hex(fields.joinToString("") { value -> if (value == null) "-1:" else "${value.length}:$value" })
}

internal fun sha256Hex(input: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(input.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
