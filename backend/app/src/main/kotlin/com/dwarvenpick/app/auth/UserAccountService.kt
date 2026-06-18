package com.dwarvenpick.app.auth

import jakarta.annotation.PostConstruct
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

class DisabledUserException(
    override val message: String = "User account is disabled.",
) : RuntimeException(message)

class UserNotFoundException(
    override val message: String,
) : RuntimeException(message)

private val localUsernamePattern = Regex("^[a-z][a-z0-9.-]*$")

data class UserAccount(
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
    private val userAccountRepository: UserAccountRepository,
) {
    @PostConstruct
    fun initializeSeedUsers() {
        seedLocalUsers(clearExisting = false)
    }

    @Synchronized
    fun resetState() {
        seedLocalUsers(clearExisting = true)
    }

    private fun seedLocalUsers(clearExisting: Boolean) {
        if (clearExisting) {
            userAccountRepository.clear()
        }
        if (!authProperties.isLocalAuthEnabled()) {
            return
        }
        authProperties.local.seedUsers.forEach { seedUser ->
            passwordPolicyValidator.validateOrThrow(seedUser.password)

            val normalizedUsername = normalizeUsername(seedUser.username)
            require(localUsernamePattern.matches(normalizedUsername)) {
                "Seed user '${seedUser.username}' has an invalid username."
            }
            if (!clearExisting && userAccountRepository.find(normalizedUsername) != null) {
                return@forEach
            }
            userAccountRepository.save(
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
                ),
            )
        }
    }

    fun listUsers(): List<AdminUserAccount> =
        userAccountRepository
            .list()
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
        if (userAccountRepository.find(normalizedUsername) != null) {
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

        val user =
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
        userAccountRepository.save(user)

        return toPrincipal(user)
    }

    fun updateLocalDisplayName(
        username: String,
        displayName: String?,
    ): AdminUserAccount {
        val normalizedUsername = normalizeUsername(username)
        val user =
            userAccountRepository.find(normalizedUsername)
                ?: throw UserNotFoundException("User '$normalizedUsername' does not exist.")

        if (!user.enabled) {
            throw DisabledUserException()
        }
        if (user.provider != AuthProvider.LOCAL) {
            throw IllegalArgumentException("User '$normalizedUsername' is not locally managed.")
        }

        val resolvedDisplayName = displayName?.trim()?.ifBlank { null } ?: user.username
        user.displayName = resolvedDisplayName
        userAccountRepository.save(user)
        return user.toAdminSummary()
    }

    fun authenticateLocal(
        username: String,
        password: String,
    ): AuthenticatedUserPrincipal? {
        val user = userAccountRepository.find(normalizeUsername(username)) ?: return null

        if (!user.enabled) {
            throw DisabledUserException()
        }

        val passwordHash = user.passwordHash ?: return null
        if (!passwordEncoder.matches(password, passwordHash)) {
            return null
        }

        userAccountRepository.save(user)
        return toPrincipal(user)
    }

    fun currentUserPrincipal(username: String): AuthenticatedUserPrincipal? {
        val user = userAccountRepository.find(normalizeUsername(username)) ?: return null
        return toPrincipal(user)
    }

    fun resetPassword(
        username: String,
        newPassword: String,
        temporaryPassword: Boolean = false,
    ): AuthenticatedUserPrincipal {
        passwordPolicyValidator.validateOrThrow(newPassword)

        val user =
            userAccountRepository.find(normalizeUsername(username))
                ?: throw UserNotFoundException("User '$username' does not exist.")

        if (!user.enabled) {
            throw DisabledUserException()
        }

        user.passwordHash = passwordEncoder.encode(newPassword)
        user.provider = AuthProvider.LOCAL
        user.temporaryPassword = temporaryPassword
        userAccountRepository.save(user)
        return toPrincipal(user)
    }

    fun provisionOrUpdateLdapUser(
        profile: LdapUserProfile,
        internalGroups: Set<String>,
        roles: Set<String>,
    ): AuthenticatedUserPrincipal {
        val normalizedUsername = normalizeUsername(profile.username)
        val existingUser = userAccountRepository.find(normalizedUsername)

        val normalizedRoles =
            roles
                .asSequence()
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
                .toMutableSet()
                .apply { add("USER") }

        if (existingUser == null) {
            val user =
                UserAccount(
                    username = normalizedUsername,
                    displayName = profile.displayName,
                    email = profile.email,
                    passwordHash = null,
                    provider = AuthProvider.LDAP,
                    enabled = true,
                    temporaryPassword = false,
                    roles = normalizedRoles,
                    groups = internalGroups.toMutableSet(),
                )
            userAccountRepository.save(user)

            return toPrincipal(user)
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
        existingUser.roles.clear()
        existingUser.roles.addAll(normalizedRoles)

        userAccountRepository.save(existingUser)
        return toPrincipal(existingUser)
    }

    fun provisionOrUpdateOidcUser(
        username: String,
        displayName: String,
        email: String?,
        internalGroups: Set<String>,
        roles: Set<String>,
    ): AuthenticatedUserPrincipal {
        val normalizedUsername = normalizeUsername(username)
        val existingUser = userAccountRepository.find(normalizedUsername)

        val normalizedRoles =
            roles
                .asSequence()
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
                .toMutableSet()
                .apply { add("USER") }

        if (existingUser == null) {
            val user =
                UserAccount(
                    username = normalizedUsername,
                    displayName = displayName,
                    email = email,
                    passwordHash = null,
                    provider = AuthProvider.OIDC,
                    enabled = true,
                    temporaryPassword = false,
                    roles = normalizedRoles,
                    groups = internalGroups.toMutableSet(),
                )
            userAccountRepository.save(user)

            return toPrincipal(user)
        }

        if (!existingUser.enabled) {
            throw DisabledUserException()
        }

        existingUser.provider = AuthProvider.OIDC
        existingUser.displayName = displayName
        existingUser.email = email
        existingUser.temporaryPassword = false
        existingUser.groups.clear()
        existingUser.groups.addAll(internalGroups)
        existingUser.roles.clear()
        existingUser.roles.addAll(normalizedRoles)

        userAccountRepository.save(existingUser)
        return toPrincipal(existingUser)
    }

    fun addGroupMembership(
        username: String,
        groupId: String,
    ): AuthenticatedUserPrincipal {
        val user =
            userAccountRepository.find(normalizeUsername(username))
                ?: throw UserNotFoundException("User '$username' does not exist.")

        if (!user.enabled) {
            throw DisabledUserException()
        }

        user.groups.add(groupId)
        userAccountRepository.save(user)
        return toPrincipal(user)
    }

    fun removeGroupMembership(
        username: String,
        groupId: String,
    ): AuthenticatedUserPrincipal {
        val user =
            userAccountRepository.find(normalizeUsername(username))
                ?: throw UserNotFoundException("User '$username' does not exist.")

        if (!user.enabled) {
            throw DisabledUserException()
        }

        user.groups.remove(groupId)
        userAccountRepository.save(user)
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
