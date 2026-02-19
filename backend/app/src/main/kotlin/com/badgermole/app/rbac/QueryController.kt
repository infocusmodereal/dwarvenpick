package com.badgermole.app.rbac

import com.badgermole.app.auth.AuthAuditEvent
import com.badgermole.app.auth.AuthAuditLogger
import com.badgermole.app.auth.AuthenticatedPrincipalResolver
import com.badgermole.app.auth.ErrorResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@RequestMapping("/api/queries")
class QueryController(
    private val rbacService: RbacService,
    private val authAuditLogger: AuthAuditLogger,
    private val authenticatedPrincipalResolver: AuthenticatedPrincipalResolver,
) {
    @PostMapping
    fun executeQuery(
        @Valid @RequestBody request: QueryExecutionRequest,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)

        return try {
            val allowed = rbacService.canUserQuery(principal, request.datasourceId)
            if (!allowed) {
                audit(
                    type = "query.execute",
                    actor = principal.username,
                    outcome = "denied",
                    httpServletRequest = httpServletRequest,
                    details =
                        mapOf(
                            "datasourceId" to request.datasourceId,
                        ),
                )
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ErrorResponse("Datasource access denied for query execution."))
            }

            val executionId = UUID.randomUUID().toString()
            audit(
                type = "query.execute",
                actor = principal.username,
                outcome = "allowed",
                httpServletRequest = httpServletRequest,
                details =
                    mapOf(
                        "datasourceId" to request.datasourceId,
                        "executionId" to executionId,
                    ),
            )

            ResponseEntity.ok(
                QueryExecutionResponse(
                    executionId = executionId,
                    datasourceId = request.datasourceId,
                    status = "QUEUED",
                    message = "Query accepted for execution.",
                ),
            )
        } catch (ex: DatasourceNotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(ex.message))
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
