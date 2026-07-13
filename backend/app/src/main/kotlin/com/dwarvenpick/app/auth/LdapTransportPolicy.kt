package com.dwarvenpick.app.auth

import java.net.URI
import java.net.URISyntaxException

data class ValidatedLdapTransport(
    val uri: URI,
    val transport: LdapTransport,
)

object LdapTransportPolicy {
    fun validate(properties: LdapAuthProperties): ValidatedLdapTransport {
        val configuredUrl = properties.url.trim()
        require(configuredUrl.isNotEmpty()) { "LDAP URL is required when LDAP authentication is enabled." }

        val uri =
            try {
                URI(configuredUrl)
            } catch (exception: URISyntaxException) {
                throw IllegalArgumentException("LDAP URL is invalid.", exception)
            }

        require(uri.host?.isNotBlank() == true) { "LDAP URL must include a host." }
        require(uri.userInfo == null) { "LDAP URL must not include user information." }
        require(uri.query == null) { "LDAP URL must not include a query." }
        require(uri.fragment == null) { "LDAP URL must not include a fragment." }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "LDAP URL must not include a path." }
        require(uri.port == -1 || uri.port > 0) { "LDAP URL port must be positive." }

        val configuredScheme = uri.scheme?.lowercase()
        val resolvedTransport =
            when (properties.transport) {
                LdapTransport.AUTO ->
                    when (configuredScheme) {
                        "ldap" -> LdapTransport.PLAIN
                        "ldaps" -> LdapTransport.LDAPS
                        else -> throw IllegalArgumentException("LDAP URL scheme must be ldap or ldaps.")
                    }
                else -> properties.transport
            }
        val expectedScheme = if (resolvedTransport == LdapTransport.LDAPS) "ldaps" else "ldap"
        require(uri.scheme.equals(expectedScheme, ignoreCase = true)) {
            "DWARVENPICK_AUTH_LDAP_TRANSPORT=${properties.transport} requires an $expectedScheme:// URL."
        }

        return ValidatedLdapTransport(uri = uri, transport = resolvedTransport)
    }
}
