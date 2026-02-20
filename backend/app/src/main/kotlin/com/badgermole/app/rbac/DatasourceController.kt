package com.badgermole.app.rbac

import com.badgermole.app.auth.AuthenticatedPrincipalResolver
import com.badgermole.app.auth.ErrorResponse
import com.badgermole.app.datasource.SchemaBrowserService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/datasources")
class DatasourceController(
    private val rbacService: RbacService,
    private val schemaBrowserService: SchemaBrowserService,
    private val authenticatedPrincipalResolver: AuthenticatedPrincipalResolver,
) {
    @GetMapping
    fun listPermittedDatasources(authentication: Authentication): List<DatasourceResponse> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return rbacService.listPermittedDatasources(principal)
    }

    @GetMapping("/{datasourceId}/schema-browser")
    fun schemaBrowser(
        @PathVariable datasourceId: String,
        @RequestParam(required = false, defaultValue = "false") refresh: Boolean,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return try {
            if (!rbacService.canUserQuery(principal, datasourceId)) {
                throw QueryAccessDeniedException("Datasource access denied for schema browser.")
            }

            val policy = rbacService.resolveQueryAccessPolicy(principal, datasourceId)
            val response =
                schemaBrowserService.fetchSchema(
                    datasourceId = datasourceId,
                    credentialProfile = policy.credentialProfile,
                    refresh = refresh,
                )
            ResponseEntity.ok(response)
        } catch (ex: DatasourceNotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(ex.message))
        } catch (ex: QueryAccessDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(ex.message))
        } catch (ex: IllegalArgumentException) {
            ResponseEntity.badRequest().body(ErrorResponse(ex.message ?: "Bad request."))
        }
    }
}
