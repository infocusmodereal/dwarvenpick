package com.dwarvenpick.app.auth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LdapUserGroupMappingTests {
    private fun service(
        mappings: List<LdapUserGroupMapping> = listOf(LdapUserGroupMapping("ALICE", setOf("temporary-deals-ro"))),
    ): LdapAuthenticationService =
        LdapAuthenticationService(
            AuthProperties(
                ldap =
                    LdapAuthProperties(
                        userGroupMappings = mappings,
                        systemAdminGroups = setOf("admins"),
                        mock =
                            LdapMockProperties(
                                enabled = true,
                                users =
                                    listOf(
                                        LdapMockUserProperties("alice", "Password123!", groups = setOf("team")),
                                        LdapMockUserProperties("bob", "Password123!"),
                                    ),
                            ),
                    ),
            ),
        ).also { it.validateConfiguration() }

    @Test
    fun `mapping requires successful authentication and exact canonical username`() {
        val service = service()
        assertThat(service.authenticate("alice", "wrong")).isNull()
        assertThat(service.authenticate("alice-other", "Password123!")).isNull()
        assertThat(service.authenticate("bob", "Password123!")!!.mappedGroups).isEmpty()
        repeat(2) {
            val result = service.authenticate(" Alice ", "Password123!")!!
            assertThat(result.mappedGroups).containsExactlyInAnyOrder("team", "temporary-deals-ro")
            assertThat(result.roles).containsExactly("USER")
        }
    }

    @Test
    fun `removing configuration removes supplemental membership at next login`() {
        assertThat(service(emptyList()).authenticate("alice", "Password123!")!!.mappedGroups).containsExactly("team")
    }

    @Test
    fun `invalid mappings and administrator groups fail configuration validation`() {
        listOf(
            LdapUserGroupMapping("*", setOf("readers")),
            LdapUserGroupMapping("", setOf("readers")),
            LdapUserGroupMapping("alice", setOf("*")),
            LdapUserGroupMapping("alice", emptySet()),
            LdapUserGroupMapping("alice", setOf(" ADMINS ")),
        ).forEach { mapping ->
            assertThatThrownBy { service(listOf(mapping)) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
