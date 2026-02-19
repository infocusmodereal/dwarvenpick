package com.badgermole.app.auth

import jakarta.annotation.PostConstruct
import java.util.concurrent.ConcurrentHashMap
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

class DisabledUserException(
    override val message: String = "User account is disabled.",
) : RuntimeException(message)

class UserNotFoundException(
    override val message: String,
) : RuntimeException(message)

private data class UserAccount(
    val username: String,
    var displayName: String,
    var email: String?,
    var passwordHash: String?,
    var provider: AuthProvider,
    var enabled: Boolean,
    val roles: MutableSet<String>,
    val groups: MutableSet<String>,
)

@Service
class UserAccountService(
    private val authProperties: AuthProperties,
    private val passwordPolicyValidator: PasswordPolicyValidator,
    private val passwordEncoder: PasswordEncoder,
) {
    private val users = ConcurrentHashMap<String, UserAccount>()

    @PostConstruct
    fun initializeSeedUsers() {
        authProperties.local.seedUsers.forEach { seedUser ->
            passwordPolicyValidator.validateOrThrow(seedUser.password)

            val normalizedUsername = normalizeUsername(seedUser.username)
            users[normalizedUsername] =
                UserAccount(
                    username = seedUser.username,
                    displayName = seedUser.displayName,
                    email = seedUser.email,
                    passwordHash = passwordEncoder.encode(seedUser.password),
                    provider = AuthProvider.LOCAL,
                    enabled = seedUser.enabled,
                    roles = seedUser.roles.map { it.uppercase() }.toMutableSet(),
                    groups = mutableSetOf(),
                )
        }
    }

    fun authenticateLocal(username: String, password: String): AuthenticatedUserPrincipal? {
        val user = users[normalizeUsername(username)] ?: return null

        if (!user.enabled) {
            throw DisabledUserException()
        }

        val passwordHash = user.passwordHash ?: return null
        if (!passwordEncoder.matches(password, passwordHash)) {
            return null
        }

        return toPrincipal(user)
    }

    fun currentUserPrincipal(username: String): AuthenticatedUserPrincipal? {
        val user = users[normalizeUsername(username)] ?: return null
        return toPrincipal(user)
    }

    fun resetPassword(username: String, newPassword: String): AuthenticatedUserPrincipal {
        passwordPolicyValidator.validateOrThrow(newPassword)

        val user =
            users[normalizeUsername(username)]
                ?: throw UserNotFoundException("User '$username' does not exist.")

        if (!user.enabled) {
            throw DisabledUserException()
        }

        user.passwordHash = passwordEncoder.encode(newPassword)
        user.provider = AuthProvider.LOCAL
        return toPrincipal(user)
    }

    fun provisionOrUpdateLdapUser(profile: LdapUserProfile, internalGroups: Set<String>): AuthenticatedUserPrincipal {
        val normalizedUsername = normalizeUsername(profile.username)
        val existingUser = users[normalizedUsername]

        if (existingUser == null) {
            users[normalizedUsername] =
                UserAccount(
                    username = profile.username,
                    displayName = profile.displayName,
                    email = profile.email,
                    passwordHash = null,
                    provider = AuthProvider.LDAP,
                    enabled = true,
                    roles = mutableSetOf("USER"),
                    groups = internalGroups.toMutableSet(),
                )

            return toPrincipal(users[normalizedUsername]!!)
        }

        if (!existingUser.enabled) {
            throw DisabledUserException()
        }

        existingUser.provider = AuthProvider.LDAP
        existingUser.displayName = profile.displayName
        existingUser.email = profile.email
        existingUser.groups.clear()
        existingUser.groups.addAll(internalGroups)
        if (existingUser.roles.isEmpty()) {
            existingUser.roles.add("USER")
        }

        return toPrincipal(existingUser)
    }

    private fun toPrincipal(userAccount: UserAccount): AuthenticatedUserPrincipal =
        AuthenticatedUserPrincipal(
            username = userAccount.username,
            displayName = userAccount.displayName,
            email = userAccount.email,
            provider = userAccount.provider,
            roles = userAccount.roles.toSet(),
            groups = userAccount.groups.toSet(),
        )

    private fun normalizeUsername(username: String): String = username.trim().lowercase()
}
