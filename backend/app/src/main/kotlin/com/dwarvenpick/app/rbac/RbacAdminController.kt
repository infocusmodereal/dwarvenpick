package com.dwarvenpick.app.rbac

import com.dwarvenpick.app.auth.AuthAuditEvent
import com.dwarvenpick.app.auth.AuthAuditLogger
import com.dwarvenpick.app.auth.DisabledUserException
import com.dwarvenpick.app.auth.ErrorResponse
import com.dwarvenpick.app.auth.UserNotFoundException
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
@RequestMapping("/api/admin")
class RbacAdminController(
    private val rbacService: RbacService,
    private val authAuditLogger: AuthAuditLogger,
) {
    @GetMapping("/groups")
    fun listGroups(): List<GroupResponse> = rbacService.listGroups()

    @PostMapping("/groups")
    fun createGroup(
        @Valid @RequestBody request: CreateGroupRequest,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        runCatching {
            val created = rbacService.createGroup(request)
            audit(
                type = "rbac.group.create",
                actor = authentication.name,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details = mapOf("groupId" to created.id),
            )
            ResponseEntity.status(HttpStatus.CREATED).body(created)
        }.getOrElse { ex -> handleGroupErrors(ex) }

    @PatchMapping("/groups/{groupId}")
    fun updateGroup(
        @PathVariable groupId: String,
        @RequestBody request: UpdateGroupRequest,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        runCatching {
            val updated = rbacService.updateGroup(groupId, request)
            audit(
                type = "rbac.group.update",
                actor = authentication.name,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details = mapOf("groupId" to updated.id),
            )
            ResponseEntity.ok(updated)
        }.getOrElse { ex -> handleGroupErrors(ex) }

    @PostMapping("/groups/{groupId}/members")
    fun addGroupMember(
        @PathVariable groupId: String,
        @Valid @RequestBody request: GroupMemberRequest,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        runCatching {
            val updated = rbacService.addMember(groupId, request.username)
            audit(
                type = "rbac.group.member_add",
                actor = authentication.name,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details =
                    mapOf(
                        "groupId" to groupId,
                        "username" to request.username,
                    ),
            )
            ResponseEntity.ok(updated)
        }.getOrElse { ex -> handleGroupErrors(ex) }

    @DeleteMapping("/groups/{groupId}/members/{username}")
    fun removeGroupMember(
        @PathVariable groupId: String,
        @PathVariable username: String,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        runCatching {
            val updated = rbacService.removeMember(groupId, username)
            audit(
                type = "rbac.group.member_remove",
                actor = authentication.name,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details =
                    mapOf(
                        "groupId" to groupId,
                        "username" to username,
                    ),
            )
            ResponseEntity.ok(updated)
        }.getOrElse { ex -> handleGroupErrors(ex) }

    @DeleteMapping("/groups/{groupId}")
    fun deleteGroup(
        @PathVariable groupId: String,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        runCatching {
            val deleted = rbacService.deleteGroup(groupId)
            audit(
                type = "rbac.group.delete",
                actor = authentication.name,
                outcome = if (deleted) "success" else "noop",
                httpServletRequest = httpServletRequest,
                details = mapOf("groupId" to groupId),
            )
            ResponseEntity.ok(mapOf("deleted" to deleted, "groupId" to groupId))
        }.getOrElse { ex -> handleGroupErrors(ex) }

    @GetMapping("/datasources")
    fun listDatasourceCatalog(): List<DatasourceResponse> = rbacService.listDatasourceCatalog()

    @GetMapping("/datasource-access")
    fun listDatasourceAccess(
        @RequestParam(required = false) groupId: String?,
    ): List<DatasourceAccessResponse> = rbacService.listDatasourceAccess(groupId)

    @PutMapping("/datasource-access/{groupId}/{datasourceId}")
    fun upsertDatasourceAccess(
        @PathVariable groupId: String,
        @PathVariable datasourceId: String,
        @Valid @RequestBody request: UpsertDatasourceAccessRequest,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        runCatching {
            val updated = rbacService.upsertDatasourceAccess(groupId, datasourceId, request)
            audit(
                type = "rbac.datasource_access.upsert",
                actor = authentication.name,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details =
                    mapOf(
                        "groupId" to groupId,
                        "datasourceId" to datasourceId,
                        "canQuery" to request.canQuery,
                        "canExport" to request.canExport,
                    ),
            )
            ResponseEntity.ok(updated)
        }.getOrElse { ex -> handleDatasourceErrors(ex) }

    @DeleteMapping("/datasource-access/{groupId}/{datasourceId}")
    fun deleteDatasourceAccess(
        @PathVariable groupId: String,
        @PathVariable datasourceId: String,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        val deleted = rbacService.deleteDatasourceAccess(groupId, datasourceId)
        audit(
            type = "rbac.datasource_access.delete",
            actor = authentication.name,
            outcome = if (deleted) "success" else "noop",
            httpServletRequest = httpServletRequest,
            details =
                mapOf(
                    "groupId" to groupId,
                    "datasourceId" to datasourceId,
                ),
        )
        return ResponseEntity.ok(mapOf("deleted" to deleted))
    }

    private fun handleGroupErrors(ex: Throwable): ResponseEntity<*> =
        when (ex) {
            is IllegalArgumentException -> ResponseEntity.badRequest().body(ErrorResponse(ex.message ?: "Bad request."))
            is GroupNotFoundException -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(ex.message))
            is UserNotFoundException -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(ex.message))
            is DisabledUserException -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(ex.message))
            else -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse("Group operation failed."))
        }

    private fun handleDatasourceErrors(ex: Throwable): ResponseEntity<*> =
        when (ex) {
            is IllegalArgumentException -> ResponseEntity.badRequest().body(ErrorResponse(ex.message ?: "Bad request."))
            is GroupNotFoundException -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(ex.message))
            is DatasourceNotFoundException -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(ex.message))
            else -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse("Datasource access operation failed."))
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
