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
class DatasourceAdminController(
    private val datasourceRegistryService: DatasourceRegistryService,
    private val datasourcePoolManager: DatasourcePoolManager,
    private val authAuditLogger: AuthAuditLogger,
) {
    @GetMapping("/drivers")
    fun listDrivers(
        @RequestParam(required = false) engine: DatasourceEngine?,
    ): List<DriverDescriptorResponse> = datasourceRegistryService.listDrivers(engine)

    @GetMapping("/datasource-management")
    fun listManagedDatasources(): List<ManagedDatasourceResponse> = datasourceRegistryService.listManagedDatasources()

    @PostMapping("/datasource-management")
    fun createDatasource(
        @Valid @RequestBody request: CreateDatasourceRequest,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        runCatching {
            val created = datasourceRegistryService.createDatasource(request)
            audit(
                type = "datasource.create",
                actor = authentication.name,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details = mapOf("datasourceId" to created.id, "engine" to created.engine),
            )
            ResponseEntity.status(HttpStatus.CREATED).body(created)
        }.getOrElse { ex -> handleDatasourceErrors(ex) }

    @PatchMapping("/datasource-management/{datasourceId}")
    fun updateDatasource(
        @PathVariable datasourceId: String,
        @RequestBody request: UpdateDatasourceRequest,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        runCatching {
            val updated = datasourceRegistryService.updateDatasource(datasourceId, request)
            datasourcePoolManager.evictPoolsForDatasource(datasourceId)
            audit(
                type = "datasource.update",
                actor = authentication.name,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details = mapOf("datasourceId" to updated.id),
            )
            ResponseEntity.ok(updated)
        }.getOrElse { ex -> handleDatasourceErrors(ex) }

    @DeleteMapping("/datasource-management/{datasourceId}")
    fun deleteDatasource(
        @PathVariable datasourceId: String,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        val deleted = datasourceRegistryService.deleteDatasource(datasourceId)
        datasourcePoolManager.evictPoolsForDatasource(datasourceId)
        audit(
            type = "datasource.delete",
            actor = authentication.name,
            outcome = if (deleted) "success" else "noop",
            httpServletRequest = httpServletRequest,
            details = mapOf("datasourceId" to datasourceId),
        )
        return ResponseEntity.ok(mapOf("deleted" to deleted))
    }

    @GetMapping("/datasource-management/{datasourceId}/credentials")
    fun listCredentialProfiles(
        @PathVariable datasourceId: String,
    ): ResponseEntity<*> =
        runCatching {
            ResponseEntity.ok(datasourceRegistryService.listCredentialProfiles(datasourceId))
        }.getOrElse { ex -> handleDatasourceErrors(ex) }

    @PutMapping("/datasource-management/{datasourceId}/credentials/{profileId}")
    fun upsertCredentialProfile(
        @PathVariable datasourceId: String,
        @PathVariable profileId: String,
        @Valid @RequestBody request: UpsertCredentialProfileRequest,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        runCatching {
            val response = datasourceRegistryService.upsertCredentialProfile(datasourceId, profileId, request)
            datasourcePoolManager.evictPoolsForDatasource(datasourceId)
            audit(
                type = "datasource.credential_profile.upsert",
                actor = authentication.name,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details =
                    mapOf(
                        "datasourceId" to datasourceId,
                        "profileId" to profileId,
                    ),
            )
            ResponseEntity.ok(response)
        }.getOrElse { ex -> handleDatasourceErrors(ex) }

    @PostMapping("/datasource-management/credentials/reencrypt")
    fun reencryptCredentialProfiles(
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<ReencryptCredentialsResponse> {
        val response = datasourceRegistryService.reencryptAllCredentialProfiles()
        datasourcePoolManager.evictAllPools()
        audit(
            type = "datasource.credentials.reencrypt",
            actor = authentication.name,
            outcome = "success",
            httpServletRequest = httpServletRequest,
            details = mapOf("updatedProfiles" to response.updatedProfiles, "keyId" to response.activeKeyId),
        )
        return ResponseEntity.ok(response)
    }

    @GetMapping("/datasource-management/pools")
    fun listPoolMetrics(): List<PoolMetricsResponse> = datasourcePoolManager.listPoolMetrics()

    private fun handleDatasourceErrors(ex: Throwable): ResponseEntity<*> =
        when (ex) {
            is IllegalArgumentException -> ResponseEntity.badRequest().body(ErrorResponse(ex.message ?: "Bad request."))
            is ManagedDatasourceNotFoundException -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(ex.message))
            is CredentialProfileNotFoundException -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(ex.message))
            is DriverNotAvailableException -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(ex.message))
            else -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse("Datasource operation failed."))
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
