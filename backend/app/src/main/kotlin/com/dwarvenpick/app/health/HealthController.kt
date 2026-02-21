package com.dwarvenpick.app.health

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/health")
class HealthController {
    @GetMapping
    fun health(): HealthResponse =
        HealthResponse(
            service = "dwarvenpick-backend",
            status = "UP",
            timestamp = Instant.now().toString(),
        )
}

data class HealthResponse(
    val service: String,
    val status: String,
    val timestamp: String,
)
