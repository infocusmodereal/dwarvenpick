package com.dwarvenpick.app.auth

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Component

@Component
class OidcAuthenticationSuccessHandler(
    private val authProperties: AuthProperties,
    private val userAccountService: UserAccountService,
    private val sessionAuthenticationService: SessionAuthenticationService,
    private val authAuditLogger: AuthAuditLogger,
    private val meterRegistry: MeterRegistry,
) : AuthenticationSuccessHandler {
    private val logger = LoggerFactory.getLogger(OidcAuthenticationSuccessHandler::class.java)

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val token = authentication as? OAuth2AuthenticationToken
        val oidcUser = token?.principal as? OidcUser
        if (oidcUser == null) {
            logger.warn("OIDC login succeeded but principal is not an OidcUser (principal={})", authentication.principal)
            recordAuthAttempt(outcome = "failed")
            redirectRelative(response, "/login?error=oidc")
            return
        }

        val mapping = authProperties.oidc.claimMapping
        val username =
            claimAsString(oidcUser, mapping.username)
                ?.trim()
                ?.ifBlank { null }
                ?: oidcUser.name.trim().ifBlank { null }
        if (username == null) {
            logger.warn("OIDC login missing username claim '{}' (sub={})", mapping.username, oidcUser.subject)
            recordAuthAttempt(outcome = "failed")
            redirectRelative(response, "/login?error=oidc")
            return
        }

        val displayName =
            claimAsString(oidcUser, mapping.displayName)
                ?.trim()
                ?.ifBlank { null }
                ?: username
        val email =
            claimAsString(oidcUser, mapping.email)
                ?.trim()
                ?.ifBlank { null }

        val groups =
            if (authProperties.oidc.groupSync.enabled) {
                val rawGroups = claimAsStringSet(oidcUser, mapping.groups)
                mapGroups(rawGroups)
            } else {
                emptySet()
            }

        val roles = resolveRoles(groups)
        val principal =
            userAccountService.provisionOrUpdateOidcUser(
                username = username,
                displayName = displayName,
                email = email,
                internalGroups = groups,
                roles = roles,
            )

        sessionAuthenticationService.establishSession(principal, request, response)

        authAuditLogger.log(
            AuthAuditEvent(
                type = "auth.oidc.login",
                actor = principal.username,
                outcome = "success",
                ipAddress = request.remoteAddr,
                details =
                    mapOf(
                        "provider" to "oidc",
                        "groups" to principal.groups,
                    ),
            ),
        )

        recordAuthAttempt(outcome = "success")
        redirectRelative(response, "/workspace")
    }

    private fun mapGroups(rawGroups: Set<String>): Set<String> {
        val mappingRules = authProperties.oidc.groupSync.mappingRules
        if (mappingRules.isEmpty()) {
            return rawGroups
        }
        return rawGroups.mapNotNull { group -> mappingRules[group] }.toSet()
    }

    private fun resolveRoles(mappedGroups: Set<String>): Set<String> {
        val roles = mutableSetOf("USER")

        val systemAdminGroups = authProperties.oidc.systemAdminGroups
        if (systemAdminGroups.isNotEmpty() && mappedGroups.any { it in systemAdminGroups }) {
            roles.add("SYSTEM_ADMIN")
        }

        return roles
    }

    private fun claimAsString(
        user: OidcUser,
        claim: String,
    ): String? =
        runCatching {
            user.getClaimAsString(claim)
        }.getOrNull()

    private fun claimAsStringSet(
        user: OidcUser,
        claim: String,
    ): Set<String> {
        val raw = user.claims[claim] ?: return emptySet()
        return when (raw) {
            is Collection<*> ->
                raw
                    .filterIsInstance<String>()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
            is Array<*> ->
                raw
                    .filterIsInstance<String>()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
            is String ->
                raw
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { setOf(it) }
                    ?: emptySet()
            else -> emptySet()
        }
    }

    private fun recordAuthAttempt(outcome: String) {
        meterRegistry
            .counter(
                "dwarvenpick.auth.login.attempts",
                "provider",
                "oidc",
                "outcome",
                outcome,
            ).increment()
    }

    private fun redirectRelative(
        response: HttpServletResponse,
        location: String,
    ) {
        response.status = HttpServletResponse.SC_FOUND
        response.setHeader("Location", location)
    }
}

@Component
class OidcAuthenticationFailureHandler(
    private val authAuditLogger: AuthAuditLogger,
    private val meterRegistry: MeterRegistry,
) : AuthenticationFailureHandler {
    private val logger = LoggerFactory.getLogger(OidcAuthenticationFailureHandler::class.java)

    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: org.springframework.security.core.AuthenticationException,
    ) {
        val message = exception.message?.takeIf { it.isNotBlank() } ?: "OIDC authentication failed."
        logger.warn("OIDC authentication failure: {}", message)

        authAuditLogger.log(
            AuthAuditEvent(
                type = "auth.oidc.login",
                actor = null,
                outcome = "failed",
                ipAddress = request.remoteAddr,
                details = mapOf("reason" to message),
            ),
        )

        meterRegistry
            .counter(
                "dwarvenpick.auth.login.attempts",
                "provider",
                "oidc",
                "outcome",
                "failed",
            ).increment()

        response.status = HttpServletResponse.SC_FOUND
        response.setHeader("Location", "/login?error=oidc")
    }
}
