package com.dwarvenpick.app.snippet

import com.dwarvenpick.app.auth.AuthAuditEvent
import com.dwarvenpick.app.auth.AuthAuditLogger
import com.dwarvenpick.app.auth.AuthenticatedPrincipalResolver
import com.dwarvenpick.app.auth.ErrorResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@RequestMapping("/api/snippets")
class SnippetController(
    private val snippetService: SnippetService,
    private val authenticatedPrincipalResolver: AuthenticatedPrincipalResolver,
    private val authAuditLogger: AuthAuditLogger,
) {
    @GetMapping
    fun listSnippets(
        @RequestParam(required = false) scope: String?,
        @RequestParam(required = false) title: String?,
        @RequestParam(required = false) titleMatch: String?,
        @RequestParam(required = false) groupId: String?,
        authentication: Authentication,
    ): List<SnippetResponse> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return snippetService.listSnippets(
            principal = principal,
            scope = scope,
            title = title,
            titleMatch = titleMatch,
            groupId = groupId,
        )
    }

    @PostMapping
    fun createSnippet(
        @Valid @RequestBody request: CreateSnippetRequest,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return try {
            val snippet = snippetService.createSnippet(principal, request)
            audit(
                type = "snippet.create",
                actor = principal.username,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details = mapOf("snippetId" to snippet.snippetId, "groupId" to snippet.groupId),
            )
            ResponseEntity.status(HttpStatus.CREATED).body(snippet as Any)
        } catch (exception: Throwable) {
            audit(
                type = "snippet.create",
                actor = principal.username,
                outcome = "failed",
                httpServletRequest = httpServletRequest,
                details = mapOf("reason" to (exception.message ?: "creation_failed")),
            )
            toErrorResponse(exception)
        }
    }

    @PatchMapping("/{snippetId}")
    fun updateSnippet(
        @PathVariable snippetId: String,
        @RequestBody request: UpdateSnippetRequest,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return try {
            val snippet = snippetService.updateSnippet(principal, snippetId, request)
            audit(
                type = "snippet.update",
                actor = principal.username,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details = mapOf("snippetId" to snippet.snippetId),
            )
            ResponseEntity.ok(snippet as Any)
        } catch (exception: Throwable) {
            audit(
                type = "snippet.update",
                actor = principal.username,
                outcome = "failed",
                httpServletRequest = httpServletRequest,
                details = mapOf("snippetId" to snippetId, "reason" to (exception.message ?: "update_failed")),
            )
            toErrorResponse(exception)
        }
    }

    @DeleteMapping("/{snippetId}")
    fun deleteSnippet(
        @PathVariable snippetId: String,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return try {
            val deleted = snippetService.deleteSnippet(principal, snippetId)
            audit(
                type = "snippet.delete",
                actor = principal.username,
                outcome = if (deleted) "success" else "noop",
                httpServletRequest = httpServletRequest,
                details = mapOf("snippetId" to snippetId),
            )
            ResponseEntity.ok(mapOf("deleted" to deleted) as Any)
        } catch (exception: Throwable) {
            audit(
                type = "snippet.delete",
                actor = principal.username,
                outcome = "failed",
                httpServletRequest = httpServletRequest,
                details = mapOf("snippetId" to snippetId, "reason" to (exception.message ?: "delete_failed")),
            )
            toErrorResponse(exception)
        }
    }

    private fun toErrorResponse(exception: Throwable): ResponseEntity<Any> =
        when (exception) {
            is SnippetNotFoundException ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(exception.message))
            is SnippetAccessDeniedException ->
                ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(exception.message))
            is IllegalArgumentException ->
                ResponseEntity.badRequest().body(ErrorResponse(exception.message ?: "Bad request."))
            else ->
                ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse("Snippet operation failed."))
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
