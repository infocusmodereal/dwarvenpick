package com.dwarvenpick.app.runtime

import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ApplicationInstanceId {
    val value: String =
        System
            .getenv("HOSTNAME")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "local-${UUID.randomUUID()}"
}
