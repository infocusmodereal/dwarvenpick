package com.badgermole.app.rbac

import com.badgermole.app.auth.AuthenticatedPrincipalResolver
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/datasources")
class DatasourceController(
    private val rbacService: RbacService,
    private val authenticatedPrincipalResolver: AuthenticatedPrincipalResolver,
) {
    @GetMapping
    fun listPermittedDatasources(authentication: Authentication): List<DatasourceResponse> {
        val principal = authenticatedPrincipalResolver.resolve(authentication)
        return rbacService.listPermittedDatasources(principal)
    }
}
