package com.dwarvenpick.app.health

import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.HealthEndpointGroups
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/health")
class HealthController(
    private val healthEndpoint: HealthEndpoint,
    private val healthEndpointGroups: HealthEndpointGroups,
) {
    @GetMapping
    fun health(): ResponseEntity<HealthResponse> {
        val readinessGroup = requireNotNull(healthEndpointGroups.get(READINESS_GROUP))
        val readiness = requireNotNull(healthEndpoint.healthForPath(READINESS_GROUP))
        val response =
            HealthResponse(
                service = "dwarvenpick-backend",
                status = readiness.status.code,
                timestamp = Instant.now().toString(),
            )

        return ResponseEntity
            .status(readinessGroup.httpCodeStatusMapper.getStatusCode(readiness.status))
            .body(response)
    }

    private companion object {
        const val READINESS_GROUP = "readiness"
    }
}

data class HealthResponse(
    val service: String,
    val status: String,
    val timestamp: String,
)
