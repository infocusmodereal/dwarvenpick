package com.dwarvenpick.app.auth

import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

@Component
class AuthenticatedPrincipalResolver(
    private val userAccountService: UserAccountService,
) {
    fun resolve(authentication: Authentication): AuthenticatedUserPrincipal =
        (authentication.principal as? AuthenticatedUserPrincipal)
            ?: userAccountService.currentUserPrincipal(authentication.name)
            ?: AuthenticatedUserPrincipal(
                username = authentication.name,
                displayName = authentication.name,
                email = null,
                provider = AuthProvider.LOCAL,
                roles = emptySet(),
                groups = emptySet(),
            )
}
