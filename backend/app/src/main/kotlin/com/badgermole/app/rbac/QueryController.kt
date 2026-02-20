package com.badgermole.app.rbac

import com.badgermole.app.auth.AuthAuditEvent
import com.badgermole.app.auth.AuthAuditLogger
import com.badgermole.app.auth.AuthenticatedPrincipalResolver
import com.badgermole.app.auth.ErrorResponse
import com.badgermole.app.datasource.DriverNotAvailableException
import com.badgermole.app.query.QueryConcurrencyLimitException
import com.badgermole.app.query.QueryExecutionManager
import com.badgermole.app.query.QueryExecutionForbiddenException
import com.badgermole.app.query.QueryExecutionNotFoundException
import com.badgermole.app.query.QueryExecutionRequest
import com.badgermole.app.query.QueryInvalidPageTokenException
import com.badgermole.app.query.QueryResultsRequest
import com.badgermole.app.query.QueryResultsExpiredException
import com.badgermole.app.query.QueryResultsNotReadyException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@Validated
@RequestMapping("/api/queries")
class QueryController(
    private val rbacService: RbacService,
    private val authAuditLogger: AuthAuditLogger,
    private val authenticatedPrincipalResolver: AuthenticatedPrincipalResolver,
    private val queryExecutionManager: QueryExecutionManager,
) {
    @PostMapping
    fun executeQuery(
        @Valid @RequestBody request: QueryExecutionRequest,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return try {
            val accessPolicy = rbacService.resolveQueryAccessPolicy(principal, request.datasourceId)
            val response =
                queryExecutionManager.submitQuery(
                    actor = principal.username,
                    ipAddress = httpServletRequest.remoteAddr,
                    request = request,
                    policy = accessPolicy,
                )
            ResponseEntity.ok(response)
        } catch (ex: QueryAccessDeniedException) {
            auditDeniedExecution(
                actor = principal.username,
                datasourceId = request.datasourceId,
                ipAddress = httpServletRequest.remoteAddr,
            )
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(ex.message))
        } catch (ex: DatasourceNotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(ex.message))
        } catch (ex: QueryConcurrencyLimitException) {
            auditLimitReached(
                actor = principal.username,
                datasourceId = request.datasourceId,
                ipAddress = httpServletRequest.remoteAddr,
                message = ex.message,
            )
            ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ErrorResponse(ex.message))
        } catch (ex: DriverNotAvailableException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(ex.message))
        } catch (ex: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ErrorResponse(ex.message ?: "Bad request."),
            )
        }
    }

    @PostMapping("/{executionId}/cancel")
    fun cancelExecution(
        @PathVariable executionId: String,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return handleQueryErrors {
            val response =
                queryExecutionManager.cancelQuery(
                    actor = principal.username,
                    isSystemAdmin = principal.roles.contains("SYSTEM_ADMIN"),
                    executionId = executionId,
                )
            ResponseEntity.ok(response)
        }
    }

    @GetMapping("/{executionId}")
    fun getExecutionStatus(
        @PathVariable executionId: String,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return handleQueryErrors {
            val response =
                queryExecutionManager.getExecutionStatus(
                    actor = principal.username,
                    isSystemAdmin = principal.roles.contains("SYSTEM_ADMIN"),
                    executionId = executionId,
                )
            ResponseEntity.ok(response)
        }
    }

    @GetMapping("/{executionId}/results")
    fun getExecutionResults(
        @PathVariable executionId: String,
        @RequestParam(required = false) pageToken: String?,
        @RequestParam(required = false) pageSize: Int?,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return handleQueryErrors {
            val response =
                queryExecutionManager.getQueryResults(
                    actor = principal.username,
                    isSystemAdmin = principal.roles.contains("SYSTEM_ADMIN"),
                    executionId = executionId,
                    request =
                        QueryResultsRequest(
                            pageToken = pageToken,
                            pageSize = pageSize,
                        ),
                )
            ResponseEntity.ok(response)
        }
    }

    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun queryStatusEvents(authentication: Authentication): SseEmitter {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return queryExecutionManager.subscribeToStatusEvents(
            actor = principal.username,
            isSystemAdmin = principal.roles.contains("SYSTEM_ADMIN"),
        )
    }

    private fun handleQueryErrors(action: () -> ResponseEntity<Any>): ResponseEntity<Any> =
        try {
            action()
        } catch (ex: QueryExecutionNotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(ex.message))
        } catch (ex: QueryExecutionForbiddenException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(ex.message))
        } catch (ex: QueryResultsNotReadyException) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message))
        } catch (ex: QueryResultsExpiredException) {
            ResponseEntity.status(HttpStatus.GONE).body(ErrorResponse(ex.message))
        } catch (ex: QueryInvalidPageTokenException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(ex.message))
        } catch (ex: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ErrorResponse(ex.message ?: "Bad request."),
            )
        }

    private fun auditDeniedExecution(
        actor: String,
        datasourceId: String,
        ipAddress: String?,
    ) {
        authAuditLogger.log(
            AuthAuditEvent(
                type = "query.execute",
                actor = actor,
                outcome = "denied",
                ipAddress = ipAddress,
                details = mapOf("datasourceId" to datasourceId),
            ),
        )
    }

    private fun auditLimitReached(
        actor: String,
        datasourceId: String,
        ipAddress: String?,
        message: String?,
    ) {
        authAuditLogger.log(
            AuthAuditEvent(
                type = "query.execute",
                actor = actor,
                outcome = "limited",
                ipAddress = ipAddress,
                details =
                    mapOf(
                        "datasourceId" to datasourceId,
                        "reason" to (message ?: "concurrency_limit"),
                    ),
            ),
        )
    }
}
