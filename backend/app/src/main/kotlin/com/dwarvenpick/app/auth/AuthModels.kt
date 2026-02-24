package com.dwarvenpick.app.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
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
    val temporary: Boolean = false,
)

data class CreateLocalUserRequest(
    @field:NotBlank(message = "Username is required.")
    @field:Pattern(
        regexp = "^[a-z][a-z0-9.-]*$",
        message = "Username must start with a letter and contain only lowercase letters, numbers, '.' and '-'.",
    )
    val username: String = "",
    val displayName: String? = null,
    @field:Email(message = "Email must be a valid email address.")
    val email: String? = null,
    @field:NotBlank(message = "Password is required.")
    val password: String = "",
    val temporaryPassword: Boolean = false,
    val systemAdmin: Boolean = false,
)

data class UpdateLocalUserRequest(
    val displayName: String? = null,
)

data class AuthenticatedUserPrincipal(
    val username: String,
    val displayName: String,
    val email: String?,
    val provider: AuthProvider,
    val roles: Set<String>,
    val groups: Set<String>,
) : Serializable,
    Principal {
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
    val temporaryPassword: Boolean = false,
)

data class AdminUserResponse(
    val username: String,
    val displayName: String,
    val email: String?,
    val provider: String,
    val enabled: Boolean,
    val roles: Set<String>,
    val groups: Set<String>,
    val temporaryPassword: Boolean,
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
