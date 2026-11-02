package com.dwarvenpick.app.auth

import jakarta.annotation.PostConstruct
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

class DisabledUserException(
    override val message: String = "User account is disabled.",
) : RuntimeException(message)

class UserNotFoundException(
    override val message: String,
) : RuntimeException(message)

class ExternalIdentityConflictException : RuntimeException("External identity must be explicitly linked by an administrator.")

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
    val userId: String = UUID.randomUUID().toString(),
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
@Transactional
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

        if (user.provider != AuthProvider.LOCAL) return null
        val passwordHash = user.passwordHash ?: return null
        if (!passwordEncoder.matches(password, passwordHash)) {
            return null
        }

        userAccountRepository.save(user)
        return toPrincipal(user)
    }

    fun currentUserPrincipal(username: String): AuthenticatedUserPrincipal? {
        val user = userAccountRepository.find(normalizeUsername(username)) ?: return null
        if (!user.enabled) throw DisabledUserException()
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

        require(user.provider == AuthProvider.LOCAL) { "External accounts cannot use local password resets." }
        user.passwordHash = passwordEncoder.encode(newPassword)
        user.provider = AuthProvider.LOCAL
        user.temporaryPassword = temporaryPassword
        userAccountRepository.save(user)
        return toPrincipal(user)
    }

    @Transactional
    fun provisionOrUpdateLdapUser(
        profile: LdapUserProfile,
        internalGroups: Set<String>,
        roles: Set<String>,
    ): AuthenticatedUserPrincipal =
        provisionExternal(
            AuthProvider.LDAP,
            authProperties.ldap.identityIssuer,
            profile.distinguishedName,
            profile.username,
            profile.displayName,
            profile.email,
            internalGroups,
            roles,
        )

    @Transactional
    fun provisionOrUpdateOidcUser(
        username: String,
        displayName: String,
        email: String?,
        internalGroups: Set<String>,
        roles: Set<String>,
        issuer: String,
        subject: String,
    ): AuthenticatedUserPrincipal =
        provisionExternal(
            AuthProvider.OIDC,
            issuer,
            subject,
            username,
            displayName,
            email,
            internalGroups,
            roles,
        )

    private fun provisionExternal(
        provider: AuthProvider,
        issuer: String,
        subject: String,
        username: String,
        displayName: String,
        email: String?,
        groups: Set<String>,
        roles: Set<String>,
    ): AuthenticatedUserPrincipal {
        require(issuer.isNotBlank() && subject.isNotBlank()) { "External identity requires issuer and subject." }
        val existing = userAccountRepository.findExternal(provider, issuer, subject)
        val account =
            existing ?: run {
                val normalized = normalizeUsername(username)
                if (userAccountRepository.find(normalized) != null) {
                    throw ExternalIdentityConflictException()
                }
                UserAccount(
                    username = normalized,
                    displayName = displayName,
                    email = email,
                    passwordHash = null,
                    provider = provider,
                    enabled = true,
                    temporaryPassword = false,
                    roles = mutableSetOf(),
                    groups = mutableSetOf(),
                )
            }
        if (!account.enabled) throw DisabledUserException()
        account.displayName = displayName
        account.email = email
        account.groups.clear()
        account.groups.addAll(groups)
        account.roles.clear()
        account.roles.addAll(roles.map { it.trim().uppercase() }.filter { it.isNotBlank() })
        account.roles.add("USER")
        userAccountRepository.save(account)
        if (existing == null) userAccountRepository.linkExternal(account, provider, issuer, subject)
        return toPrincipal(account)
    }

    @Transactional
    fun linkExternalIdentity(
        username: String,
        provider: AuthProvider,
        issuer: String,
        subject: String,
    ) {
        require(provider != AuthProvider.LOCAL && issuer.isNotBlank() && subject.isNotBlank()) {
            "An external provider, issuer and subject are required."
        }
        val account = userAccountRepository.find(normalizeUsername(username)) ?: throw UserNotFoundException("User not found.")
        require(account.provider == provider) { "Provider must match the existing account; automatic provider conversion is not allowed." }
        userAccountRepository.linkExternal(account, provider, issuer, subject)
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
            userId = userAccount.userId,
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
