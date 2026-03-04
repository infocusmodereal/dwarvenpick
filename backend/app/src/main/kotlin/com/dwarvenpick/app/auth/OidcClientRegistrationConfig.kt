package com.dwarvenpick.app.auth

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod

@Configuration
@ConditionalOnProperty(prefix = "dwarvenpick.auth.oidc", name = ["enabled"], havingValue = "true")
class OidcClientRegistrationConfig(
    private val authProperties: AuthProperties,
) {
    @Bean
    fun clientRegistrationRepository(): ClientRegistrationRepository {
        val oidc = authProperties.oidc
        require(oidc.issuerUri.isNotBlank()) { "dwarvenpick.auth.oidc.issuer-uri is required when OIDC is enabled." }
        require(oidc.clientId.isNotBlank()) { "dwarvenpick.auth.oidc.client-id is required when OIDC is enabled." }
        require(oidc.clientSecret.isNotBlank()) {
            "dwarvenpick.auth.oidc.client-secret is required when OIDC is enabled."
        }

        val registration = buildRegistration(oidc)

        return InMemoryClientRegistrationRepository(registration)
    }

    @Bean
    fun authorizedClientService(clientRegistrationRepository: ClientRegistrationRepository): OAuth2AuthorizedClientService =
        InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository)

    private fun buildRegistration(oidc: OidcAuthProperties): ClientRegistration {
        val issuer = oidc.issuerUri.trim().trimEnd('/')
        val isKeycloakRealm = issuer.contains("/realms/")

        val authorizationUri =
            oidc.authorizationUri.trim().ifBlank {
                if (isKeycloakRealm) {
                    "$issuer/protocol/openid-connect/auth"
                } else {
                    ""
                }
            }
        val tokenUri =
            oidc.tokenUri.trim().ifBlank {
                if (isKeycloakRealm) {
                    "$issuer/protocol/openid-connect/token"
                } else {
                    ""
                }
            }
        val jwkSetUri =
            oidc.jwkSetUri.trim().ifBlank {
                if (isKeycloakRealm) {
                    "$issuer/protocol/openid-connect/certs"
                } else {
                    ""
                }
            }
        val userInfoUri = oidc.userInfoUri.trim()

        require(authorizationUri.isNotBlank()) {
            "dwarvenpick.auth.oidc.authorization-uri is required (or use a Keycloak realm issuer-uri)."
        }
        require(tokenUri.isNotBlank()) {
            "dwarvenpick.auth.oidc.token-uri is required (or use a Keycloak realm issuer-uri)."
        }
        require(jwkSetUri.isNotBlank()) {
            "dwarvenpick.auth.oidc.jwk-set-uri is required (or use a Keycloak realm issuer-uri)."
        }

        val builder =
            ClientRegistration
                .withRegistrationId("oidc")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .clientId(oidc.clientId)
                .clientSecret(oidc.clientSecret)
                .redirectUri(oidc.redirectUriTemplate)
                .scope(oidc.scopes)
                .issuerUri(issuer)
                .authorizationUri(authorizationUri)
                .tokenUri(tokenUri)
                .jwkSetUri(jwkSetUri)
                .userNameAttributeName(oidc.claimMapping.username)
                .clientName("OIDC")

        if (userInfoUri.isNotBlank()) {
            builder.userInfoUri(userInfoUri)
        }

        return builder.build()
    }
}
