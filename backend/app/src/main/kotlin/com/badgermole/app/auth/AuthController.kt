package com.badgermole.app.auth

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
@RequestMapping("/api/auth")
class AuthController(
    private val userAccountService: UserAccountService,
    private val ldapAuthenticationService: LdapAuthenticationService,
    private val authProperties: AuthProperties,
    private val meterRegistry: MeterRegistry,
    private val authenticatedPrincipalResolver: AuthenticatedPrincipalResolver,
    private val authAuditLogger: AuthAuditLogger,
    private val securityContextRepository: SecurityContextRepository,
) {
    @GetMapping("/csrf")
    fun csrfToken(csrfToken: CsrfToken): CsrfTokenResponse =
        CsrfTokenResponse(
            token = csrfToken.token,
            headerName = csrfToken.headerName,
            parameterName = csrfToken.parameterName,
        )

    @GetMapping("/methods")
    fun methods(): AuthMethodsResponse {
        val methods = mutableListOf<String>()
        if (authProperties.local.enabled) {
            methods.add("local")
        }
        if (authProperties.ldap.enabled || authProperties.ldap.mock.enabled) {
            methods.add("ldap")
        }
        return AuthMethodsResponse(methods = methods)
    }

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        httpServletRequest: HttpServletRequest,
        httpServletResponse: HttpServletResponse,
    ): ResponseEntity<Any> {
        if (!authProperties.local.enabled) {
            recordAuthAttempt(provider = "local", outcome = "unsupported")
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse("Local authentication is disabled."))
        }

        return try {
            val principal = userAccountService.authenticateLocal(request.username, request.password)

            if (principal == null) {
                audit(
                    type = "auth.local.login",
                    actor = request.username,
                    outcome = "failed",
                    httpServletRequest = httpServletRequest,
                    details = mapOf("reason" to "invalid_credentials"),
                )
                recordAuthAttempt(provider = "local", outcome = "failed")
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse("Invalid username or password."))
            } else {
                establishSession(principal, httpServletRequest, httpServletResponse)

                audit(
                    type = "auth.local.login",
                    actor = principal.username,
                    outcome = "success",
                    httpServletRequest = httpServletRequest,
                    details = mapOf("provider" to "local"),
                )
                recordAuthAttempt(provider = "local", outcome = "success")

                ResponseEntity.ok(principal.toLoginResponse())
            }
        } catch (ex: DisabledUserException) {
            audit(
                type = "auth.local.login",
                actor = request.username,
                outcome = "failed",
                httpServletRequest = httpServletRequest,
                details = mapOf("reason" to "user_disabled"),
            )
            recordAuthAttempt(provider = "local", outcome = "failed")
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(ex.message))
        }
    }

    @PostMapping("/ldap/login")
    fun ldapLogin(
        @Valid @RequestBody request: LoginRequest,
        httpServletRequest: HttpServletRequest,
        httpServletResponse: HttpServletResponse,
    ): ResponseEntity<Any> {
        if (!authProperties.ldap.enabled && !authProperties.ldap.mock.enabled) {
            recordAuthAttempt(provider = "ldap", outcome = "unsupported")
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse("LDAP authentication is disabled."))
        }

        val ldapResult = ldapAuthenticationService.authenticate(request.username, request.password)

        if (ldapResult == null) {
            audit(
                type = "auth.ldap.login",
                actor = request.username,
                outcome = "failed",
                httpServletRequest = httpServletRequest,
                details = mapOf("reason" to "invalid_credentials_or_configuration"),
            )
            recordAuthAttempt(provider = "ldap", outcome = "failed")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse("LDAP authentication failed. Check your credentials and LDAP configuration."))
        }

        return try {
            val previousGroups = userAccountService.currentUserPrincipal(ldapResult.profile.username)?.groups ?: emptySet()
            val principal =
                userAccountService.provisionOrUpdateLdapUser(
                    profile = ldapResult.profile,
                    internalGroups = ldapResult.mappedGroups,
                )

            establishSession(principal, httpServletRequest, httpServletResponse)

            val addedGroups = ldapResult.mappedGroups - previousGroups
            val removedGroups = previousGroups - ldapResult.mappedGroups
            if (addedGroups.isNotEmpty() || removedGroups.isNotEmpty()) {
                audit(
                    type = "auth.ldap.group_sync",
                    actor = principal.username,
                    outcome = "success",
                    httpServletRequest = httpServletRequest,
                    details =
                        mapOf(
                            "added" to addedGroups,
                            "removed" to removedGroups,
                        ),
                )
            }

            audit(
                type = "auth.ldap.login",
                actor = principal.username,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details =
                    mapOf(
                        "provider" to "ldap",
                        "groups" to principal.groups,
                    ),
            )
            recordAuthAttempt(provider = "ldap", outcome = "success")

            ResponseEntity.ok(principal.toLoginResponse())
        } catch (ex: DisabledUserException) {
            audit(
                type = "auth.ldap.login",
                actor = request.username,
                outcome = "failed",
                httpServletRequest = httpServletRequest,
                details = mapOf("reason" to "user_disabled"),
            )
            recordAuthAttempt(provider = "ldap", outcome = "failed")
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(ex.message))
        }
    }

    @PostMapping("/logout")
    fun logout(
        authentication: Authentication?,
        httpServletRequest: HttpServletRequest,
        httpServletResponse: HttpServletResponse,
    ): ResponseEntity<Map<String, String>> {
        val username = authentication?.name
        httpServletRequest.getSession(false)?.invalidate()

        val emptyContext = SecurityContextHolder.createEmptyContext()
        SecurityContextHolder.setContext(emptyContext)
        securityContextRepository.saveContext(emptyContext, httpServletRequest, httpServletResponse)

        audit(
            type = "auth.logout",
            actor = username,
            outcome = "success",
            httpServletRequest = httpServletRequest,
            details = emptyMap(),
        )

        return ResponseEntity.ok(mapOf("status" to "logged_out"))
    }

    @GetMapping("/me")
    fun me(authentication: Authentication): CurrentUserResponse =
        authenticatedPrincipalResolver.resolve(authentication).toCurrentUserResponse()

    @PostMapping("/admin/users/{username}/reset-password")
    fun adminResetPassword(
        @PathVariable username: String,
        @Valid @RequestBody request: PasswordResetRequest,
        authentication: Authentication,
        httpServletRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        return try {
            val updatedUser = userAccountService.resetPassword(username, request.newPassword)

            audit(
                type = "auth.password_reset",
                actor = authentication.name,
                outcome = "success",
                httpServletRequest = httpServletRequest,
                details =
                    mapOf(
                        "targetUser" to updatedUser.username,
                    ),
            )

            ResponseEntity.ok(
                PasswordResetResponse(
                    username = updatedUser.username,
                    message = "Password reset completed.",
                ),
            )
        } catch (ex: PasswordPolicyException) {
            ResponseEntity.badRequest().body(ErrorResponse(ex.message))
        } catch (ex: DisabledUserException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(ex.message))
        } catch (ex: UserNotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(ex.message))
        }
    }

    private fun establishSession(
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
        httpServletResponse.addCookie(
            Cookie("JSESSIONID", session.id).apply {
                path = "/"
                isHttpOnly = true
            },
        )
        securityContextRepository.saveContext(context, httpServletRequest, httpServletResponse)
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

    private fun AuthenticatedUserPrincipal.toLoginResponse(): LoginResponse =
        LoginResponse(
            username = username,
            displayName = displayName,
            email = email,
            provider = provider.name.lowercase(),
        )

    private fun AuthenticatedUserPrincipal.toCurrentUserResponse(): CurrentUserResponse =
        CurrentUserResponse(
            username = username,
            displayName = displayName,
            email = email,
            provider = provider.name.lowercase(),
            roles = roles,
            groups = groups,
        )

    private fun recordAuthAttempt(
        provider: String,
        outcome: String,
    ) {
        meterRegistry.counter(
            "badgermole.auth.login.attempts",
            "provider",
            provider,
            "outcome",
            outcome,
        ).increment()
    }
}
