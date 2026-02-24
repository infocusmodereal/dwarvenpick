package com.dwarvenpick.app.auth

import jakarta.annotation.PostConstruct
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

class DisabledUserException(
    override val message: String = "User account is disabled.",
) : RuntimeException(message)

class UserNotFoundException(
    override val message: String,
) : RuntimeException(message)

private val localUsernamePattern = Regex("^[a-z][a-z0-9.-]*$")

private data class UserAccount(
    val username: String,
    var displayName: String,
    var email: String?,
    var passwordHash: String?,
    var provider: AuthProvider,
    var enabled: Boolean,
    var temporaryPassword: Boolean,
    val roles: MutableSet<String>,
    val groups: MutableSet<String>,
)

data class AdminUserAccount(
    val username: String,
    val displayName: String,
    val email: String?,
    val provider: AuthProvider,
    val enabled: Boolean,
    val roles: Set<String>,
    val groups: Set<String>,
    val temporaryPassword: Boolean,
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
        resetState()
    }

    @Synchronized
    fun resetState() {
        users.clear()
        authProperties.local.seedUsers.forEach { seedUser ->
            passwordPolicyValidator.validateOrThrow(seedUser.password)

            val normalizedUsername = normalizeUsername(seedUser.username)
            require(localUsernamePattern.matches(normalizedUsername)) {
                "Seed user '${seedUser.username}' has an invalid username."
            }
            users[normalizedUsername] =
                UserAccount(
                    username = normalizedUsername,
                    displayName = seedUser.displayName,
                    email = seedUser.email,
                    passwordHash = passwordEncoder.encode(seedUser.password),
                    provider = AuthProvider.LOCAL,
                    enabled = seedUser.enabled,
                    temporaryPassword = false,
                    roles = seedUser.roles.map { it.uppercase() }.toMutableSet(),
                    groups = mutableSetOf(),
                )
        }
    }

    fun listUsers(): List<AdminUserAccount> =
        users.values
            .sortedBy { account -> account.username.lowercase() }
            .map { account -> account.toAdminSummary() }

    fun createLocalUser(
        username: String,
        displayName: String?,
        email: String?,
        password: String,
        temporaryPassword: Boolean,
        systemAdmin: Boolean,
    ): AuthenticatedUserPrincipal {
        passwordPolicyValidator.validateOrThrow(password)
        val normalizedUsername = normalizeUsername(username)
        if (normalizedUsername.isBlank()) {
            throw IllegalArgumentException("Username is required.")
        }
        if (!localUsernamePattern.matches(normalizedUsername)) {
            throw IllegalArgumentException(
                "Username must start with a letter and contain only lowercase letters, numbers, '.' and '-'.",
            )
        }
        if (users.containsKey(normalizedUsername)) {
            throw IllegalArgumentException("User '$normalizedUsername' already exists.")
        }

        val resolvedDisplayName = displayName?.trim()?.ifBlank { null } ?: username.trim()
        val resolvedEmail = email?.trim()?.ifBlank { null }
        val roles =
            if (systemAdmin) {
                mutableSetOf("SYSTEM_ADMIN", "USER")
            } else {
                mutableSetOf("USER")
            }

        users[normalizedUsername] =
            UserAccount(
                username = normalizedUsername,
                displayName = resolvedDisplayName,
                email = resolvedEmail,
                passwordHash = passwordEncoder.encode(password),
                provider = AuthProvider.LOCAL,
                enabled = true,
                temporaryPassword = temporaryPassword,
                roles = roles,
                groups = mutableSetOf(),
            )

        return toPrincipal(users.getValue(normalizedUsername))
    }

    fun updateLocalDisplayName(
        username: String,
        displayName: String?,
    ): AdminUserAccount {
        val normalizedUsername = normalizeUsername(username)
        val user =
            users[normalizedUsername]
                ?: throw UserNotFoundException("User '$normalizedUsername' does not exist.")

        if (!user.enabled) {
            throw DisabledUserException()
        }
        if (user.provider != AuthProvider.LOCAL) {
            throw IllegalArgumentException("User '$normalizedUsername' is LDAP managed.")
        }

        val resolvedDisplayName = displayName?.trim()?.ifBlank { null } ?: user.username
        user.displayName = resolvedDisplayName
        return user.toAdminSummary()
    }

    fun authenticateLocal(
        username: String,
        password: String,
    ): AuthenticatedUserPrincipal? {
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

    fun resetPassword(
        username: String,
        newPassword: String,
        temporaryPassword: Boolean = false,
    ): AuthenticatedUserPrincipal {
        passwordPolicyValidator.validateOrThrow(newPassword)

        val user =
            users[normalizeUsername(username)]
                ?: throw UserNotFoundException("User '$username' does not exist.")

        if (!user.enabled) {
            throw DisabledUserException()
        }

        user.passwordHash = passwordEncoder.encode(newPassword)
        user.provider = AuthProvider.LOCAL
        user.temporaryPassword = temporaryPassword
        return toPrincipal(user)
    }

    fun provisionOrUpdateLdapUser(
        profile: LdapUserProfile,
        internalGroups: Set<String>,
    ): AuthenticatedUserPrincipal {
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
                    temporaryPassword = false,
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
        existingUser.temporaryPassword = false
        existingUser.groups.clear()
        existingUser.groups.addAll(internalGroups)
        if (existingUser.roles.isEmpty()) {
            existingUser.roles.add("USER")
        }

        return toPrincipal(existingUser)
    }

    fun addGroupMembership(
        username: String,
        groupId: String,
    ): AuthenticatedUserPrincipal {
        val user =
            users[normalizeUsername(username)]
                ?: throw UserNotFoundException("User '$username' does not exist.")

        if (!user.enabled) {
            throw DisabledUserException()
        }

        user.groups.add(groupId)
        return toPrincipal(user)
    }

    fun removeGroupMembership(
        username: String,
        groupId: String,
    ): AuthenticatedUserPrincipal {
        val user =
            users[normalizeUsername(username)]
                ?: throw UserNotFoundException("User '$username' does not exist.")

        if (!user.enabled) {
            throw DisabledUserException()
        }

        user.groups.remove(groupId)
        return toPrincipal(user)
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

    private fun UserAccount.toAdminSummary(): AdminUserAccount =
        AdminUserAccount(
            username = username,
            displayName = displayName,
            email = email,
            provider = provider,
            enabled = enabled,
            roles = roles.toSet(),
            groups = groups.toSet(),
            temporaryPassword = temporaryPassword,
        )

    private fun normalizeUsername(username: String): String = username.trim().lowercase()
}
