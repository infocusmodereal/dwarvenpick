package com.dwarvenpick.app.auth

import org.springframework.stereotype.Component

class PasswordPolicyException(
    override val message: String,
) : RuntimeException(message)

@Component
class PasswordPolicyValidator(
    private val authProperties: AuthProperties,
) {
    fun validateOrThrow(password: String) {
        val policy = authProperties.passwordPolicy

        if (password.length < policy.minLength) {
            throw PasswordPolicyException("Password must be at least ${policy.minLength} characters long.")
        }

        if (policy.requireUppercase && password.none { it.isUpperCase() }) {
            throw PasswordPolicyException("Password must include at least one uppercase letter.")
        }

        if (policy.requireLowercase && password.none { it.isLowerCase() }) {
            throw PasswordPolicyException("Password must include at least one lowercase letter.")
        }

        if (policy.requireDigit && password.none { it.isDigit() }) {
            throw PasswordPolicyException("Password must include at least one digit.")
        }
    }
}
