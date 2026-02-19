package com.badgermole.app.auth

import org.slf4j.LoggerFactory
import org.springframework.ldap.core.AttributesMapper
import org.springframework.ldap.core.ContextMapper
import org.springframework.ldap.core.DirContextOperations
import org.springframework.ldap.core.LdapTemplate
import org.springframework.ldap.core.support.LdapContextSource
import org.springframework.ldap.support.LdapEncoder
import org.springframework.stereotype.Service

data class LdapUserProfile(
    val username: String,
    val displayName: String,
    val email: String?,
    val distinguishedName: String,
)

data class LdapAuthenticationResult(
    val profile: LdapUserProfile,
    val mappedGroups: Set<String>,
)

@Service
class LdapAuthenticationService(
    private val authProperties: AuthProperties,
) {
    private val logger = LoggerFactory.getLogger(LdapAuthenticationService::class.java)

    fun authenticate(username: String, password: String): LdapAuthenticationResult? {
        val trimmedUsername = username.trim()
        val trimmedPassword = password.trim()

        if (trimmedUsername.isBlank() || trimmedPassword.isBlank()) {
            return null
        }

        if (authProperties.ldap.mock.enabled) {
            return authenticateWithMock(trimmedUsername, trimmedPassword)
        }

        if (!authProperties.ldap.enabled) {
            return null
        }

        return runCatching {
            val ldapTemplate = createLdapTemplate()
            val escapedUsername = LdapEncoder.filterEncode(trimmedUsername)
            val userFilter = authProperties.ldap.userFilter.replace("{0}", escapedUsername)

            val authenticated =
                ldapTemplate.authenticate(
                    authProperties.ldap.userSearchBase,
                    userFilter,
                    trimmedPassword,
                )

            if (!authenticated) {
                return null
            }

            val profile = lookupUserProfile(ldapTemplate, userFilter, trimmedUsername) ?: return null
            val mappedGroups = resolveMappedGroups(ldapTemplate, profile)

            LdapAuthenticationResult(
                profile = profile,
                mappedGroups = mappedGroups,
            )
        }.getOrElse { ex ->
            logger.warn("LDAP authentication failed for user '{}': {}", trimmedUsername, ex.message)
            null
        }
    }

    private fun authenticateWithMock(username: String, password: String): LdapAuthenticationResult? {
        val mockUser =
            authProperties.ldap.mock.users.firstOrNull {
                it.username.equals(username, ignoreCase = true) && it.password == password
            } ?: return null

        val profile =
            LdapUserProfile(
                username = mockUser.username,
                displayName = mockUser.displayName,
                email = mockUser.email,
                distinguishedName = "uid=${mockUser.username},ou=people,dc=example,dc=org",
            )

        val mappedGroups = mapGroups(mockUser.groups)
        return LdapAuthenticationResult(profile = profile, mappedGroups = mappedGroups)
    }

    private fun lookupUserProfile(
        ldapTemplate: LdapTemplate,
        userFilter: String,
        fallbackUsername: String,
    ): LdapUserProfile? {
        val mapping = authProperties.ldap.attributeMapping
        val searchResults =
            ldapTemplate.search(
                authProperties.ldap.userSearchBase,
                userFilter,
                ContextMapper { context ->
                    val dirContext = context as DirContextOperations
                    val username =
                        dirContext.getStringAttribute(mapping.username)?.ifBlank { null } ?: fallbackUsername
                    val displayName =
                        dirContext.getStringAttribute(mapping.displayName)?.ifBlank { null } ?: username
                    val email = dirContext.getStringAttribute(mapping.email)
                    val distinguishedName = dirContext.nameInNamespace

                    LdapUserProfile(
                        username = username,
                        displayName = displayName,
                        email = email,
                        distinguishedName = distinguishedName,
                    )
                },
            )

        return searchResults.firstOrNull()
    }

    private fun resolveMappedGroups(ldapTemplate: LdapTemplate, profile: LdapUserProfile): Set<String> {
        if (!authProperties.ldap.groupSync.enabled) {
            return emptySet()
        }

        val escapedDn = LdapEncoder.filterEncode(profile.distinguishedName)
        val groupFilter = authProperties.ldap.groupSync.groupFilter.replace("{0}", escapedDn)
        val groupSearchBase = authProperties.ldap.groupSync.groupSearchBase
        val groupNameAttribute = authProperties.ldap.groupSync.groupNameAttribute

        val ldapGroups =
            ldapTemplate.search(
                groupSearchBase,
                groupFilter,
                AttributesMapper<String> { attributes ->
                    attributes.get(groupNameAttribute)?.get()?.toString() ?: ""
                },
            ).filter { it.isNotBlank() }.toSet()

        return mapGroups(ldapGroups)
    }

    private fun mapGroups(ldapGroups: Set<String>): Set<String> {
        val mappingRules = authProperties.ldap.groupSync.mappingRules
        if (mappingRules.isEmpty()) {
            return ldapGroups
        }

        return ldapGroups.mapNotNull { mappingRules[it] }.toSet()
    }

    private fun createLdapTemplate(): LdapTemplate {
        val contextSource =
            LdapContextSource().apply {
                setUrl(authProperties.ldap.url)
                if (authProperties.ldap.bindDn.isNotBlank()) {
                    userDn = authProperties.ldap.bindDn
                }
                if (authProperties.ldap.bindPassword.isNotBlank()) {
                    setPassword(authProperties.ldap.bindPassword)
                }
                afterPropertiesSet()
            }

        return LdapTemplate(contextSource)
    }
}
