package com.badgermole.app.auth

import jakarta.validation.constraints.NotBlank
import java.io.Serializable
import java.security.Principal

enum class AuthProvider {
    LOCAL,
    LDAP,
}

data class LoginRequest(
    @field:NotBlank(message = "Username is required.")
    val username: String = "",
    @field:NotBlank(message = "Password is required.")
    val password: String = "",
)

data class PasswordResetRequest(
    @field:NotBlank(message = "New password is required.")
    val newPassword: String = "",
)

data class AuthenticatedUserPrincipal(
    val username: String,
    val displayName: String,
    val email: String?,
    val provider: AuthProvider,
    val roles: Set<String>,
    val groups: Set<String>,
) : Serializable, Principal {
    override fun getName(): String = username
}

data class LoginResponse(
    val username: String,
    val displayName: String,
    val email: String?,
    val provider: String,
)

data class CurrentUserResponse(
    val username: String,
    val displayName: String,
    val email: String?,
    val provider: String,
    val roles: Set<String>,
    val groups: Set<String>,
)

data class PasswordResetResponse(
    val username: String,
    val message: String,
)

data class CsrfTokenResponse(
    val token: String,
    val headerName: String,
    val parameterName: String,
)

data class AuthMethodsResponse(
    val methods: List<String>,
)

data class ErrorResponse(
    val error: String,
)
