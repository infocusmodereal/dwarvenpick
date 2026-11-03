package com.dwarvenpick.app.auth

import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

@Component
class AuthenticatedPrincipalResolver(
    private val userAccountService: UserAccountService,
) {
    fun resolve(authentication: Authentication): AuthenticatedUserPrincipal {
        if (authentication is org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken) {
            throw org.springframework.security.access
                .AccessDeniedException("External identity has not established an application session.")
        }
        val sessionPrincipal = authentication.principal as? AuthenticatedUserPrincipal
        val current =
            try {
                userAccountService.currentUserPrincipal(authentication.name)
            } catch (_: DisabledUserException) {
                throw org.springframework.security.access
                    .AccessDeniedException("User account is disabled.")
            }
                ?: throw org.springframework.security.access
                    .AccessDeniedException("User account no longer exists.")
        if (sessionPrincipal?.userId != null && sessionPrincipal.userId != current.userId) {
            throw org.springframework.security.access
                .AccessDeniedException("Session identity no longer matches this account.")
        }
        return current
    }
}
