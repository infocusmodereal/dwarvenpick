package com.badgermole.app.health

import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/health")
class HealthController {
    @GetMapping
    fun health(): HealthResponse =
        HealthResponse(
            service = "badgermole-backend",
            status = "UP",
            timestamp = Instant.now().toString(),
        )
}

data class HealthResponse(
    val service: String,
    val status: String,
    val timestamp: String,
)
