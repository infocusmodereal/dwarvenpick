package com.dwarvenpick.app.rbac

import com.dwarvenpick.app.auth.AuthAuditEvent
import com.dwarvenpick.app.auth.AuthAuditLogger
import com.dwarvenpick.app.auth.AuthenticatedPrincipalResolver
import com.dwarvenpick.app.auth.ErrorResponse
import com.dwarvenpick.app.datasource.DriverNotAvailableException
import com.dwarvenpick.app.query.QueryConcurrencyLimitException
import com.dwarvenpick.app.query.QueryCsvWriter
import com.dwarvenpick.app.query.QueryExecutionForbiddenException
import com.dwarvenpick.app.query.QueryExecutionManager
import com.dwarvenpick.app.query.QueryExecutionNotFoundException
import com.dwarvenpick.app.query.QueryExecutionProperties
import com.dwarvenpick.app.query.QueryExecutionRequest
import com.dwarvenpick.app.query.QueryExecutionStatus
import com.dwarvenpick.app.query.QueryExportLimitExceededException
import com.dwarvenpick.app.query.QueryInvalidPageTokenException
import com.dwarvenpick.app.query.QueryReadOnlyViolationException
import com.dwarvenpick.app.query.QueryResultsExpiredException
import com.dwarvenpick.app.query.QueryResultsNotReadyException
import com.dwarvenpick.app.query.QueryResultsRequest
import io.micrometer.core.instrument.MeterRegistry
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
import java.io.ByteArrayOutputStream
import java.time.Instant

@RestController
@Validated
@RequestMapping("/api/queries")
class QueryController(
    private val rbacService: RbacService,
    private val authAuditLogger: AuthAuditLogger,
    private val authenticatedPrincipalResolver: AuthenticatedPrincipalResolver,
    private val queryExecutionManager: QueryExecutionManager,
    private val queryExecutionProperties: QueryExecutionProperties,
    private val meterRegistry: MeterRegistry,
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
        } catch (ex: QueryReadOnlyViolationException) {
            auditDeniedExecution(
                actor = principal.username,
                datasourceId = request.datasourceId,
                ipAddress = httpServletRequest.remoteAddr,
            )
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(ex.message))
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

    @GetMapping("/{executionId}/export.csv", produces = ["text/csv"])
    fun exportExecutionResultsCsv(
        @PathVariable executionId: String,
        @RequestParam(name = "headers", required = false, defaultValue = "true") headers: Boolean,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<ByteArray> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return try {
            val status =
                queryExecutionManager.getExecutionStatus(
                    actor = principal.username,
                    isSystemAdmin = principal.roles.contains("SYSTEM_ADMIN"),
                    executionId = executionId,
                )

            val allowedToExport = rbacService.canUserExport(principal, status.datasourceId)
            if (!allowedToExport) {
                recordExportMetric(outcome = "denied", datasourceId = status.datasourceId)
                authAuditLogger.log(
                    AuthAuditEvent(
                        type = "query.export",
                        actor = principal.username,
                        outcome = "denied",
                        ipAddress = httpServletRequest.remoteAddr,
                        details =
                            mapOf(
                                "executionId" to executionId,
                                "datasourceId" to status.datasourceId,
                            ),
                    ),
                )
                return ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(toCsvError("Datasource export access denied for this query."))
            }

            val exportPayload =
                queryExecutionManager.prepareCsvExport(
                    actor = principal.username,
                    isSystemAdmin = principal.roles.contains("SYSTEM_ADMIN"),
                    executionId = executionId,
                    includeHeaders = headers,
                    maxExportRows = queryExecutionProperties.maxExportRows,
                )

            authAuditLogger.log(
                AuthAuditEvent(
                    type = "query.export",
                    actor = principal.username,
                    outcome = "success",
                    ipAddress = httpServletRequest.remoteAddr,
                    details =
                        mapOf(
                            "executionId" to executionId,
                            "datasourceId" to exportPayload.datasourceId,
                            "rowCount" to exportPayload.rowCount,
                            "headers" to headers,
                        ),
                ),
            )
            recordExportMetric(outcome = "success", datasourceId = exportPayload.datasourceId)

            val outputStream = ByteArrayOutputStream()
            QueryCsvWriter.writeCsv(
                outputStream = outputStream,
                columns = exportPayload.columns,
                rows = exportPayload.rows,
                includeHeaders = headers,
            )

            ResponseEntity
                .ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header("Content-Disposition", "attachment; filename=\"query-$executionId.csv\"")
                .body(outputStream.toByteArray())
        } catch (ex: QueryExecutionNotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(toCsvError(ex.message))
        } catch (ex: QueryExecutionForbiddenException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(toCsvError(ex.message))
        } catch (ex: QueryResultsNotReadyException) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(toCsvError(ex.message))
        } catch (ex: QueryResultsExpiredException) {
            ResponseEntity.status(HttpStatus.GONE).body(toCsvError(ex.message))
        } catch (ex: QueryInvalidPageTokenException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(toCsvError(ex.message))
        } catch (ex: QueryExportLimitExceededException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(toCsvError(ex.message))
        } catch (ex: QueryAccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(toCsvError(ex.message))
        } catch (ex: QueryReadOnlyViolationException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(toCsvError(ex.message))
        } catch (ex: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(toCsvError(ex.message ?: "Bad request."))
        }
    }

    @GetMapping("/history")
    fun queryHistory(
        @RequestParam(required = false) datasourceId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) actor: String?,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return try {
            val fromInstant = parseInstantParam("from", from)
            val toInstant = parseInstantParam("to", to)
            if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
                throw IllegalArgumentException("from must be less than or equal to to.")
            }

            val statusFilter =
                status?.trim()?.takeIf { it.isNotBlank() }?.let { value ->
                    QueryExecutionStatus.valueOf(value.uppercase())
                }

            val history =
                queryExecutionManager.listHistory(
                    actor = principal.username,
                    isSystemAdmin = principal.roles.contains("SYSTEM_ADMIN"),
                    datasourceId = datasourceId,
                    status = statusFilter,
                    from = fromInstant,
                    to = toInstant,
                    limit = limit ?: 100,
                    actorFilter = actor,
                )
            ResponseEntity.ok(history)
        } catch (ex: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(ex.message ?: "Bad request."))
        }
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
        } catch (ex: QueryExportLimitExceededException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(ex.message))
        } catch (ex: QueryAccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(ex.message))
        } catch (ex: QueryReadOnlyViolationException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(ex.message))
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

    private fun recordExportMetric(
        outcome: String,
        datasourceId: String,
    ) {
        meterRegistry
            .counter(
                "dwarvenpick.query.export.attempts",
                "outcome",
                outcome,
                "datasourceId",
                datasourceId,
            ).increment()
    }

    private fun parseInstantParam(
        name: String,
        value: String?,
    ): Instant? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Instant.parse(trimmed) }
            .getOrElse {
                throw IllegalArgumentException("$name must be an ISO-8601 timestamp.")
            }
    }

    private fun toCsvError(message: String?): ByteArray {
        val safeMessage = message ?: "Bad request."
        val escaped = safeMessage.replace("\"", "\"\"")
        return "error\n\"$escaped\"\n".toByteArray(Charsets.UTF_8)
    }
}
