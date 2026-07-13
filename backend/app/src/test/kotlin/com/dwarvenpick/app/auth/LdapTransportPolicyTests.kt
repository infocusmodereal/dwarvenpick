package com.dwarvenpick.app.auth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.ldap.core.support.DefaultTlsDirContextAuthenticationStrategy
import org.springframework.test.util.ReflectionTestUtils
import java.util.stream.Stream

class LdapTransportPolicyTests {
    @Test
    fun `auto transport remains the backward compatible default`() {
        assertThat(LdapAuthProperties().transport).isEqualTo(LdapTransport.AUTO)
    }

    @Test
    fun `transport binds from configuration`() {
        val source =
            MapConfigurationPropertySource(
                mapOf(
                    "dwarvenpick.auth.ldap.url" to "ldap://directory.example.com:389",
                    "dwarvenpick.auth.ldap.transport" to "START_TLS",
                ),
            )
        val properties = Binder(source).bind("dwarvenpick.auth", Bindable.of(AuthProperties::class.java)).get()

        assertThat(properties.ldap.transport).isEqualTo(LdapTransport.START_TLS)
    }

    @Test
    fun `unknown transport fails configuration binding`() {
        val source =
            MapConfigurationPropertySource(
                mapOf("dwarvenpick.auth.ldap.transport" to "INSECURE_TLS"),
            )

        assertThatThrownBy {
            Binder(source).bind("dwarvenpick.auth", Bindable.of(AuthProperties::class.java)).get()
        }.hasMessageContaining("dwarvenpick.auth.ldap.transport")
            .rootCause()
            .hasMessageContaining("INSECURE_TLS")
    }

    @ParameterizedTest
    @MethodSource("validTransports")
    fun `valid transport and URL combinations are accepted`(
        transport: LdapTransport,
        url: String,
        expectedPort: Int,
        expectedTransport: LdapTransport,
    ) {
        val validated = LdapTransportPolicy.validate(LdapAuthProperties(url = url, transport = transport))

        assertThat(validated.transport).isEqualTo(expectedTransport)
        assertThat(validated.uri.host).isEqualTo("directory.example.com")
        assertThat(validated.uri.port).isEqualTo(expectedPort)
    }

    @ParameterizedTest
    @MethodSource("invalidTransports")
    fun `invalid LDAP URLs fail before context creation`(
        transport: LdapTransport,
        url: String,
        expectedMessage: String,
    ) {
        val service =
            LdapAuthenticationService(
                AuthProperties(ldap = LdapAuthProperties(enabled = true, url = url, transport = transport)),
            )

        assertThatThrownBy { service.createContextSource() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(expectedMessage)
    }

    @Test
    fun `start TLS installs the Spring TLS authentication strategy without pooling`() {
        val service =
            LdapAuthenticationService(
                AuthProperties(
                    ldap =
                        LdapAuthProperties(
                            enabled = true,
                            url = "ldap://directory.example.com:389",
                            transport = LdapTransport.START_TLS,
                        ),
                ),
            )

        val contextSource = service.createContextSource()

        assertThat(contextSource.isPooled).isFalse()
        assertThat(ReflectionTestUtils.getField(contextSource, "authenticationStrategy"))
            .isInstanceOf(DefaultTlsDirContextAuthenticationStrategy::class.java)
    }

    @ParameterizedTest
    @MethodSource("nonStartTlsTransports")
    fun `plain and LDAPS do not install a custom authentication strategy`(
        transport: LdapTransport,
        url: String,
    ) {
        val service =
            LdapAuthenticationService(
                AuthProperties(ldap = LdapAuthProperties(enabled = true, url = url, transport = transport)),
            )

        val contextSource = service.createContextSource()

        assertThat(ReflectionTestUtils.getField(contextSource, "authenticationStrategy"))
            .isNotInstanceOf(DefaultTlsDirContextAuthenticationStrategy::class.java)
    }

    @Test
    fun `enabled LDAP validates transport during service initialization`() {
        val service =
            LdapAuthenticationService(
                AuthProperties(
                    ldap =
                        LdapAuthProperties(
                            enabled = true,
                            url = "ldap://directory.example.com:389",
                            transport = LdapTransport.LDAPS,
                        ),
                ),
            )

        assertThatThrownBy { service.validateConfiguration() }
            .hasMessageContaining("requires an ldaps:// URL")
    }

    companion object {
        @JvmStatic
        fun validTransports(): Stream<Arguments> =
            Stream.of(
                Arguments.of(LdapTransport.PLAIN, "ldap://directory.example.com", -1, LdapTransport.PLAIN),
                Arguments.of(LdapTransport.START_TLS, "ldap://directory.example.com:389", 389, LdapTransport.START_TLS),
                Arguments.of(LdapTransport.LDAPS, "ldaps://directory.example.com:636", 636, LdapTransport.LDAPS),
                Arguments.of(LdapTransport.AUTO, "ldap://directory.example.com:389", 389, LdapTransport.PLAIN),
                Arguments.of(LdapTransport.AUTO, "ldaps://directory.example.com:636", 636, LdapTransport.LDAPS),
            )

        @JvmStatic
        fun invalidTransports(): Stream<Arguments> =
            Stream.of(
                Arguments.of(LdapTransport.PLAIN, "", "LDAP URL is required"),
                Arguments.of(LdapTransport.PLAIN, "ldap://directory.example.com/%", "LDAP URL is invalid"),
                Arguments.of(LdapTransport.PLAIN, "ldaps://directory.example.com:636", "requires an ldap:// URL"),
                Arguments.of(LdapTransport.START_TLS, "ldaps://directory.example.com:636", "requires an ldap:// URL"),
                Arguments.of(LdapTransport.LDAPS, "ldap://directory.example.com:389", "requires an ldaps:// URL"),
                Arguments.of(LdapTransport.START_TLS, "ldap:///missing-host", "must include a host"),
                Arguments.of(LdapTransport.START_TLS, "ldap://user@directory.example.com", "must not include user information"),
                Arguments.of(LdapTransport.START_TLS, "ldap://directory.example.com?scope=sub", "must not include a query"),
                Arguments.of(LdapTransport.START_TLS, "ldap://directory.example.com#fragment", "must not include a fragment"),
                Arguments.of(LdapTransport.START_TLS, "ldap://directory.example.com/ou=people", "must not include a path"),
                Arguments.of(LdapTransport.START_TLS, "ldap://directory.example.com:0", "port must be positive"),
            )

        @JvmStatic
        fun nonStartTlsTransports(): Stream<Arguments> =
            Stream.of(
                Arguments.of(LdapTransport.PLAIN, "ldap://directory.example.com:389"),
                Arguments.of(LdapTransport.LDAPS, "ldaps://directory.example.com:636"),
            )
    }
}
