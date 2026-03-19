package com.dwarvenpick.app.resource

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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@RequestMapping("/api/resources")
class ResourceController(
    private val resourceService: ResourceService,
    private val authenticatedPrincipalResolver: AuthenticatedPrincipalResolver,
    private val authAuditLogger: AuthAuditLogger,
) {
    @GetMapping
    fun listResources(
        @RequestParam(required = false) scope: String?,
        @RequestParam(required = false, name = "q") query: String?,
        @RequestParam(required = false) groupId: String?,
        @RequestParam(required = false) datasourceId: String?,
        @RequestParam(required = false) tag: String?,
        authentication: Authentication,
    ): List<ResourceScriptResponse> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return resourceService.listResources(principal, scope, query, groupId, datasourceId, tag)
    }

    @GetMapping("/{resourceId}")
    fun getResource(
        @PathVariable resourceId: String,
        authentication: Authentication,
    ): ResourceScriptResponse {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return resourceService.getResource(principal, resourceId)
    }

    @GetMapping("/{resourceId}/versions")
    fun listResourceVersions(
        @PathVariable resourceId: String,
        authentication: Authentication,
    ): List<ResourceVersionResponse> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return resourceService.listVersions(principal, resourceId)
    }

    @PostMapping
    fun createResource(
        @Valid @RequestBody request: CreateResourceRequest,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return try {
            val resource = resourceService.createResource(principal, request)
            audit(
                type = "resource.create",
                actor = principal.username,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details = mapOf("resourceId" to resource.resourceId, "scope" to resource.scope.name, "groupId" to resource.groupId),
            )
            ResponseEntity.status(HttpStatus.CREATED).body(resource as Any)
        } catch (exception: Throwable) {
            audit(
                type = "resource.create",
                actor = principal.username,
                outcome = "failed",
                httpServletRequest = httpServletRequest,
                details = mapOf("reason" to (exception.message ?: "creation_failed")),
            )
            toErrorResponse(exception)
        }
    }

    @PutMapping("/{resourceId}")
    fun updateResourceMetadata(
        @PathVariable resourceId: String,
        @Valid @RequestBody request: UpdateResourceMetadataRequest,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return try {
            val resource = resourceService.updateMetadata(principal, resourceId, request)
            audit(
                type = "resource.update",
                actor = principal.username,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details =
                    mapOf(
                        "resourceId" to resource.resourceId,
                        "scope" to resource.scope.name,
                        "groupId" to resource.groupId,
                        "tags" to resource.tags,
                    ),
            )
            ResponseEntity.ok(resource as Any)
        } catch (exception: Throwable) {
            audit(
                type = "resource.update",
                actor = principal.username,
                outcome = "failed",
                httpServletRequest = httpServletRequest,
                details = mapOf("resourceId" to resourceId, "reason" to (exception.message ?: "update_failed")),
            )
            toErrorResponse(exception)
        }
    }

    @PatchMapping("/{resourceId}/content")
    fun updateResourceContent(
        @PathVariable resourceId: String,
        @Valid @RequestBody request: UpdateResourceContentRequest,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return try {
            val resource = resourceService.updateContent(principal, resourceId, request)
            audit(
                type = "resource.content_update",
                actor = principal.username,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details = mapOf("resourceId" to resource.resourceId),
            )
            ResponseEntity.ok(resource as Any)
        } catch (exception: Throwable) {
            audit(
                type = "resource.content_update",
                actor = principal.username,
                outcome = "failed",
                httpServletRequest = httpServletRequest,
                details = mapOf("resourceId" to resourceId, "reason" to (exception.message ?: "content_update_failed")),
            )
            toErrorResponse(exception)
        }
    }

    @PostMapping("/{resourceId}/duplicate")
    fun duplicateResource(
        @PathVariable resourceId: String,
        @RequestBody(required = false) request: DuplicateResourceRequest?,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return try {
            val duplicate = resourceService.duplicateResource(principal, resourceId, request ?: DuplicateResourceRequest())
            audit(
                type = "resource.duplicate",
                actor = principal.username,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details = mapOf("sourceResourceId" to resourceId, "resourceId" to duplicate.resourceId),
            )
            ResponseEntity.status(HttpStatus.CREATED).body(duplicate as Any)
        } catch (exception: Throwable) {
            audit(
                type = "resource.duplicate",
                actor = principal.username,
                outcome = "failed",
                httpServletRequest = httpServletRequest,
                details = mapOf("sourceResourceId" to resourceId, "reason" to (exception.message ?: "duplicate_failed")),
            )
            toErrorResponse(exception)
        }
    }

    @DeleteMapping("/{resourceId}")
    fun deleteResource(
        @PathVariable resourceId: String,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return try {
            val deleted = resourceService.deleteResource(principal, resourceId)
            audit(
                type = "resource.delete",
                actor = principal.username,
                outcome = if (deleted) "success" else "noop",
                httpServletRequest = httpServletRequest,
                details = mapOf("resourceId" to resourceId),
            )
            ResponseEntity.ok(mapOf("deleted" to deleted) as Any)
        } catch (exception: Throwable) {
            audit(
                type = "resource.delete",
                actor = principal.username,
                outcome = "failed",
                httpServletRequest = httpServletRequest,
                details = mapOf("resourceId" to resourceId, "reason" to (exception.message ?: "delete_failed")),
            )
            toErrorResponse(exception)
        }
    }

    private fun toErrorResponse(exception: Throwable): ResponseEntity<Any> =
        when (exception) {
            is ResourceNotFoundException ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(exception.message))
            is ResourceAccessDeniedException ->
                ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(exception.message))
            is IllegalArgumentException ->
                ResponseEntity.badRequest().body(ErrorResponse(exception.message ?: "Bad request."))
            else ->
                ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse("Resource operation failed."))
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

    @PostMapping("/{resourceId}/versions/{versionId}/restore")
    fun restoreResourceVersion(
        @PathVariable resourceId: String,
        @PathVariable versionId: String,
        @RequestBody(required = false) request: RestoreResourceVersionRequest?,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return try {
            val resource =
                resourceService.restoreVersion(
                    principal,
                    resourceId,
                    versionId,
                    request ?: RestoreResourceVersionRequest(),
                )
            audit(
                type = "resource.restore",
                actor = principal.username,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details =
                    mapOf(
                        "resourceId" to resource.resourceId,
                        "versionId" to versionId,
                        "revision" to resource.currentRevision,
                    ),
            )
            ResponseEntity.ok(resource as Any)
        } catch (exception: Throwable) {
            audit(
                type = "resource.restore",
                actor = principal.username,
                outcome = "failed",
                httpServletRequest = httpServletRequest,
                details =
                    mapOf(
                        "resourceId" to resourceId,
                        "versionId" to versionId,
                        "reason" to (exception.message ?: "restore_failed"),
                    ),
            )
            toErrorResponse(exception)
        }
    }
}
