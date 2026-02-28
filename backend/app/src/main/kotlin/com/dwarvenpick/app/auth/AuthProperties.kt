package com.dwarvenpick.app.auth

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dwarvenpick.auth")
data class AuthProperties(
    val local: LocalAuthProperties = LocalAuthProperties(),
    val passwordPolicy: PasswordPolicyProperties = PasswordPolicyProperties(),
    val ldap: LdapAuthProperties = LdapAuthProperties(),
)

data class LocalAuthProperties(
    val enabled: Boolean = true,
    val seedUsers: List<SeedUserProperties> =
        listOf(
            SeedUserProperties(
                username = "admin",
                displayName = "Administrator",
                email = "admin@dwarvenpick.local",
                password = "Admin1234!",
                roles = setOf("SYSTEM_ADMIN", "USER"),
                enabled = true,
            ),
            SeedUserProperties(
                username = "analyst",
                displayName = "Analyst User",
                email = "analyst@dwarvenpick.local",
                password = "Analyst123!",
                roles = setOf("USER"),
                enabled = true,
            ),
            SeedUserProperties(
                username = "disabled.user",
                displayName = "Disabled User",
                email = "disabled@dwarvenpick.local",
                password = "Disabled123!",
                roles = setOf("USER"),
                enabled = false,
            ),
        ),
)

data class SeedUserProperties(
    val username: String,
    val displayName: String,
    val email: String? = null,
    val password: String,
    val roles: Set<String> = setOf("USER"),
    val enabled: Boolean = true,
)

data class PasswordPolicyProperties(
    val minLength: Int = 10,
    val requireUppercase: Boolean = true,
    val requireLowercase: Boolean = true,
    val requireDigit: Boolean = true,
)

data class LdapAuthProperties(
    val enabled: Boolean = false,
    val url: String = "",
    val bindDn: String = "",
    val bindPassword: String = "",
    val userSearchBase: String = "",
    val userFilter: String = "(uid={0})",
    val attributeMapping: LdapAttributeMappingProperties = LdapAttributeMappingProperties(),
    val groupSync: LdapGroupSyncProperties = LdapGroupSyncProperties(),
    val systemAdminGroups: Set<String> = emptySet(),
    val mock: LdapMockProperties = LdapMockProperties(),
)

data class LdapAttributeMappingProperties(
    val username: String = "uid",
    val displayName: String = "cn",
    val email: String = "mail",
)

data class LdapGroupSyncProperties(
    val enabled: Boolean = true,
    val groupSearchBase: String = "",
    val groupFilter: String = "(member={0})",
    val groupNameAttribute: String = "cn",
    val mappingRules: Map<String, String> = emptyMap(),
)

data class LdapMockProperties(
    val enabled: Boolean = false,
    val users: List<LdapMockUserProperties> = emptyList(),
)

data class LdapMockUserProperties(
    val username: String,
    val password: String,
    val displayName: String = username,
    val email: String? = null,
    val groups: Set<String> = emptySet(),
)
