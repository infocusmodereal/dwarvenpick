package com.dwarvenpick.app.query

import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Base64

@Component
class QueryResultPageTokenCodec {
    fun parse(
        executionId: String,
        pageToken: String,
    ): Int {
        val decoded =
            runCatching {
                String(
                    Base64.getUrlDecoder().decode(pageToken),
                    StandardCharsets.UTF_8,
                )
            }.getOrElse {
                throw QueryInvalidPageTokenException("pageToken is invalid.")
            }

        val parts = decoded.split(":")
        if (parts.size != 2 || parts[0] != executionId) {
            throw QueryInvalidPageTokenException("pageToken does not match this execution id.")
        }

        return parts[1].toIntOrNull()?.takeIf { it >= 0 }
            ?: throw QueryInvalidPageTokenException("pageToken offset is invalid.")
    }

    fun build(
        executionId: String,
        offset: Int,
    ): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString("$executionId:$offset".toByteArray(StandardCharsets.UTF_8))
}
