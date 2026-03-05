package com.dwarvenpick.app.controlplane

import com.dwarvenpick.app.auth.AuthAuditEvent
import com.dwarvenpick.app.auth.AuthAuditLogger
import com.dwarvenpick.app.auth.AuthenticatedPrincipalResolver
import com.dwarvenpick.app.auth.ErrorResponse
import com.dwarvenpick.app.datasource.ManagedDatasourceNotFoundException
import com.dwarvenpick.app.query.QueryCsvWriter
import com.dwarvenpick.app.query.QueryExecutionNotFoundException
import com.dwarvenpick.app.query.QueryKillResponse
import com.dwarvenpick.app.query.QueryResultColumn
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayOutputStream

@RestController
@Validated
@RequestMapping("/api/admin/control-plane")
class ControlPlaneController(
    private val controlPlaneService: ControlPlaneService,
    private val authenticatedPrincipalResolver: AuthenticatedPrincipalResolver,
    private val authAuditLogger: AuthAuditLogger,
) {
    @GetMapping
    fun status(
        @RequestParam datasourceId: String,
        @RequestParam(name = "windowSeconds", required = false, defaultValue = "900") windowSeconds: Long,
        authentication: Authentication,
    ): ResponseEntity<*> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return runCatching {
            ResponseEntity.ok(
                controlPlaneService.status(
                    actor = principal.username,
                    datasourceId = datasourceId,
                    windowSeconds = windowSeconds,
                ),
            )
        }.getOrElse { exception ->
            val message = exception.message?.takeIf { it.isNotBlank() } ?: "Control plane status failed."
            when (exception) {
                is IllegalArgumentException ->
                    ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(message))
                is ManagedDatasourceNotFoundException ->
                    ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(message))
                else -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(message))
            }
        }
    }

    @PostMapping("/datasources/{datasourceId}/pause")
    fun pause(
        @PathVariable datasourceId: String,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        controlPlaneService.pause(datasourceId)
        audit(
            type = "control_plane.datasource.pause",
            actor = principal.username,
            details = mapOf("datasourceId" to datasourceId),
        )
        return ResponseEntity.ok(mapOf("paused" to true))
    }

    @PostMapping("/datasources/{datasourceId}/resume")
    fun resume(
        @PathVariable datasourceId: String,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        controlPlaneService.resume(datasourceId)
        audit(
            type = "control_plane.datasource.resume",
            actor = principal.username,
            details = mapOf("datasourceId" to datasourceId),
        )
        return ResponseEntity.ok(mapOf("paused" to false))
    }

    @PostMapping("/datasources/{datasourceId}/queries/cancel")
    fun cancelQueries(
        @PathVariable datasourceId: String,
        @RequestParam(name = "actor", required = false) targetActor: String?,
        authentication: Authentication,
    ): ResponseEntity<*> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return runCatching {
            val response =
                controlPlaneService.cancelAll(
                    actor = principal.username,
                    datasourceId = datasourceId,
                    targetActor = targetActor,
                )
            audit(
                type = "control_plane.query.cancel.bulk",
                actor = principal.username,
                details =
                    mapOf(
                        "datasourceId" to datasourceId,
                        "targetActor" to targetActor,
                        "matched" to response.matched,
                        "succeeded" to response.succeeded,
                        "failed" to response.failed,
                    ),
            )
            ResponseEntity.ok(response)
        }.getOrElse { exception ->
            val message = exception.message?.takeIf { it.isNotBlank() } ?: "Cancel operation failed."
            when (exception) {
                is IllegalArgumentException ->
                    ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(message))
                else -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(message))
            }
        }
    }

    @PostMapping("/datasources/{datasourceId}/queries/kill")
    fun killQueries(
        @PathVariable datasourceId: String,
        @RequestParam(name = "actor", required = false) targetActor: String?,
        authentication: Authentication,
    ): ResponseEntity<*> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return runCatching {
            val response =
                controlPlaneService.killAll(
                    actor = principal.username,
                    datasourceId = datasourceId,
                    targetActor = targetActor,
                )
            audit(
                type = "control_plane.query.kill.bulk",
                actor = principal.username,
                details =
                    mapOf(
                        "datasourceId" to datasourceId,
                        "targetActor" to targetActor,
                        "matched" to response.matched,
                        "succeeded" to response.succeeded,
                        "failed" to response.failed,
                    ),
            )
            ResponseEntity.ok(response)
        }.getOrElse { exception ->
            val message = exception.message?.takeIf { it.isNotBlank() } ?: "Kill operation failed."
            when (exception) {
                is IllegalArgumentException ->
                    ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(message))
                else -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(message))
            }
        }
    }

    @PostMapping("/queries/{executionId}/kill")
    fun killQuery(
        @PathVariable executionId: String,
        authentication: Authentication,
    ): ResponseEntity<*> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return runCatching {
            val response: QueryKillResponse =
                controlPlaneService.killQuery(
                    actor = principal.username,
                    executionId = executionId,
                )
            audit(
                type = "control_plane.query.kill",
                actor = principal.username,
                details = mapOf("executionId" to executionId),
            )
            ResponseEntity.ok(response)
        }.getOrElse { exception ->
            val message = exception.message?.takeIf { it.isNotBlank() } ?: "Kill operation failed."
            when (exception) {
                is QueryExecutionNotFoundException ->
                    ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(message))
                else -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(message))
            }
        }
    }

    @GetMapping("/datasources/{datasourceId}/queries.csv", produces = ["text/csv"])
    fun exportActiveQueriesCsv(
        @PathVariable datasourceId: String,
        @RequestParam(name = "actor", required = false) targetActor: String?,
        authentication: Authentication,
    ): ResponseEntity<ByteArray> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        val status =
            controlPlaneService.status(
                actor = principal.username,
                datasourceId = datasourceId,
                windowSeconds = 900,
            )
        val queries =
            status.activeQueries
                .asSequence()
                .filter { query ->
                    query.status == "QUEUED" || query.status == "RUNNING"
                }.filter { query ->
                    targetActor.isNullOrBlank() || query.actor == targetActor.trim()
                }.toList()

        val columns =
            listOf(
                QueryResultColumn("execution_id", "VARCHAR"),
                QueryResultColumn("actor", "VARCHAR"),
                QueryResultColumn("datasource_id", "VARCHAR"),
                QueryResultColumn("credential_profile", "VARCHAR"),
                QueryResultColumn("status", "VARCHAR"),
                QueryResultColumn("submitted_at", "VARCHAR"),
                QueryResultColumn("started_at", "VARCHAR"),
                QueryResultColumn("duration_ms", "BIGINT"),
                QueryResultColumn("query_hash", "VARCHAR"),
                QueryResultColumn("sql_preview", "VARCHAR"),
                QueryResultColumn("message", "VARCHAR"),
                QueryResultColumn("cancel_requested", "BOOLEAN"),
            )
        val rows =
            queries.map { query ->
                listOf(
                    query.executionId,
                    query.actor,
                    query.datasourceId,
                    query.credentialProfile,
                    query.status,
                    query.submittedAt,
                    query.startedAt,
                    query.durationMs?.toString(),
                    query.queryHash,
                    query.sqlPreview,
                    query.message,
                    query.cancelRequested.toString(),
                )
            }

        val outputStream = ByteArrayOutputStream()
        QueryCsvWriter.writeCsv(outputStream, columns, rows, includeHeaders = true)
        val filename = "dwarvenpick-active-queries-$datasourceId.csv"

        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(outputStream.toByteArray())
    }

    private fun audit(
        type: String,
        actor: String?,
        details: Map<String, Any?>,
    ) {
        authAuditLogger.log(
            AuthAuditEvent(
                type = type,
                actor = actor,
                outcome = "success",
                ipAddress = null,
                details = details,
            ),
        )
    }
}
