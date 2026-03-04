package com.dwarvenpick.app.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.stereotype.Service

@Service
class SessionAuthenticationService(
    private val securityContextRepository: SecurityContextRepository,
) {
    fun establishSession(
        principal: AuthenticatedUserPrincipal,
        httpServletRequest: HttpServletRequest,
        httpServletResponse: HttpServletResponse,
    ) {
        val authorities = principal.roles.map { SimpleGrantedAuthority("ROLE_${it.uppercase()}") }
        val authenticationToken =
            UsernamePasswordAuthenticationToken(
                principal,
                null,
                authorities,
            )

        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authenticationToken
        SecurityContextHolder.setContext(context)
        val session = httpServletRequest.getSession(true)
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context)
        securityContextRepository.saveContext(context, httpServletRequest, httpServletResponse)
    }
}
