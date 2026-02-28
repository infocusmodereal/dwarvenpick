package com.dwarvenpick.app.systemhealth

import com.dwarvenpick.app.auth.ErrorResponse
import com.dwarvenpick.app.datasource.CredentialProfileNotFoundException
import com.dwarvenpick.app.datasource.ManagedDatasourceNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@RequestMapping("/api/admin/system-health")
class SystemHealthController(
    private val systemHealthService: SystemHealthService,
) {
    @GetMapping
    fun check(
        @RequestParam datasourceId: String,
        @RequestParam credentialProfile: String,
    ): ResponseEntity<*> =
        runCatching {
            ResponseEntity.ok(
                systemHealthService.check(
                    datasourceId = datasourceId,
                    credentialProfile = credentialProfile,
                ),
            )
        }.getOrElse { exception ->
            val message = exception.message?.takeIf { it.isNotBlank() } ?: "System health check failed."
            when (exception) {
                is ManagedDatasourceNotFoundException ->
                    ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(message))
                is CredentialProfileNotFoundException ->
                    ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(message))
                is IllegalArgumentException ->
                    ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(message))
                else -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(message))
            }
        }
}
