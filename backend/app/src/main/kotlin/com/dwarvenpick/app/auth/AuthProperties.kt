package com.dwarvenpick.app.auth

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dwarvenpick.auth")
data class AuthProperties(
    val local: LocalAuthProperties = LocalAuthProperties(),
    val passwordPolicy: PasswordPolicyProperties = PasswordPolicyProperties(),
    val ldap: LdapAuthProperties = LdapAuthProperties(),
    val oidc: OidcAuthProperties = OidcAuthProperties(),
) {
    fun isLdapAuthEnabled(): Boolean = ldap.enabled || ldap.mock.enabled

    fun isOidcAuthEnabled(): Boolean = oidc.enabled

    fun isLocalAuthEnabled(): Boolean {
        if (!local.enabled) {
            return false
        }

        if (isLdapAuthEnabled() && !local.allowWithLdap) {
            return false
        }

        if (isOidcAuthEnabled() && !local.allowWithOidc) {
            return false
        }

        return true
    }
}

data class LocalAuthProperties(
    val enabled: Boolean = true,
    /**
     * When LDAP is enabled, local authentication is disabled by default to avoid accidental backdoor access via seeded
     * users. Set this to true to keep local authentication enabled alongside LDAP (useful for local development).
     */
    val allowWithLdap: Boolean = false,
    /**
     * When OIDC is enabled, local authentication is disabled by default (same principle as LDAP). Set this to true to
     * keep local authentication enabled alongside OIDC (useful for local development / break-glass access).
     */
    val allowWithOidc: Boolean = false,
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

data class OidcAuthProperties(
    val enabled: Boolean = false,
    /**
     * OIDC issuer URI (ex: Keycloak realm URL): https://<host>/realms/<realm>
     */
    val issuerUri: String = "",
    /**
     * Optional override for the authorization endpoint. Leave blank to auto-derive for Keycloak issuers.
     */
    val authorizationUri: String = "",
    /**
     * Optional override for the token endpoint. Leave blank to auto-derive for Keycloak issuers.
     */
    val tokenUri: String = "",
    /**
     * Optional override for the JWK set endpoint. Leave blank to auto-derive for Keycloak issuers.
     */
    val jwkSetUri: String = "",
    /**
     * Optional override for the userinfo endpoint. Leave blank to skip calling userinfo and rely on ID token claims.
     */
    val userInfoUri: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val scopes: Set<String> = setOf("openid", "profile", "email"),
    /**
     * Spring Security redirect URI template. Keep the default unless you need a custom callback path.
     */
    val redirectUriTemplate: String = "{baseUrl}/login/oauth2/code/{registrationId}",
    val claimMapping: OidcClaimMappingProperties = OidcClaimMappingProperties(),
    val groupSync: OidcGroupSyncProperties = OidcGroupSyncProperties(),
    val systemAdminGroups: Set<String> = emptySet(),
)

data class OidcClaimMappingProperties(
    val username: String = "preferred_username",
    val displayName: String = "name",
    val email: String = "email",
    val groups: String = "groups",
)

data class OidcGroupSyncProperties(
    val enabled: Boolean = true,
    val mappingRules: Map<String, String> = emptyMap(),
)
