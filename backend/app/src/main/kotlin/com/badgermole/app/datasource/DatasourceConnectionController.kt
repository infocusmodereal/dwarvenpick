package com.badgermole.app.datasource

import com.badgermole.app.auth.AuthAuditEvent
import com.badgermole.app.auth.AuthAuditLogger
import com.badgermole.app.auth.ErrorResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@RequestMapping("/api/datasources")
class DatasourceConnectionController(
    private val datasourcePoolManager: DatasourcePoolManager,
    private val authAuditLogger: AuthAuditLogger,
) {
    @PostMapping("/{datasourceId}/test-connection")
    fun testConnection(
        @PathVariable datasourceId: String,
        @Valid @RequestBody request: TestConnectionRequest,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        runCatching {
            val response =
                datasourcePoolManager.testConnection(
                    datasourceId = datasourceId,
                    credentialProfile = request.credentialProfile,
                    tlsOverride = request.tls,
                    validationQuery = request.validationQuery,
                )

            audit(
                type = "datasource.test_connection",
                actor = authentication.name,
                outcome = if (response.success) "success" else "failed",
                httpServletRequest = httpServletRequest,
                details =
                    mapOf(
                        "datasourceId" to datasourceId,
                        "credentialProfile" to request.credentialProfile,
                        "driverId" to response.driverId,
                    ),
            )

            ResponseEntity.ok(response)
        }.getOrElse { exception ->
            val errorMessage = exception.message ?: "Connection test failed."
            audit(
                type = "datasource.test_connection",
                actor = authentication.name,
                outcome = "failed",
                httpServletRequest = httpServletRequest,
                details =
                    mapOf(
                        "datasourceId" to datasourceId,
                        "credentialProfile" to request.credentialProfile,
                        "reason" to errorMessage,
                    ),
            )

            when (exception) {
                is ManagedDatasourceNotFoundException ->
                    ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(errorMessage))
                is CredentialProfileNotFoundException ->
                    ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(errorMessage))
                is DriverNotAvailableException ->
                    ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(errorMessage))
                else -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(errorMessage))
            }
        }

    private fun audit(
        type: String,
        actor: String?,
        outcome: String,
        httpServletRequest: HttpServletRequest,
        details: Map<String, Any?>,
    ) {
        authAuditLogger.log(
            AuthAuditEvent(
                type = type,
                actor = actor,
                outcome = outcome,
                ipAddress = httpServletRequest.remoteAddr,
                details = details,
            ),
        )
    }
}
