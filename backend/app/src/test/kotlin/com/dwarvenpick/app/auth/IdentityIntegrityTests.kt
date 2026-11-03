package com.dwarvenpick.app.auth

import com.dwarvenpick.app.rbac.CreateGroupRequest
import com.dwarvenpick.app.rbac.RbacService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class IdentityIntegrityTests {
    @Autowired private lateinit var users: UserAccountService

    @Autowired private lateinit var accounts: UserAccountRepository

    @Autowired private lateinit var rbac: RbacService

    @BeforeEach fun reset() {
        users.resetState()
        rbac.resetState()
    }

    @Test fun `concurrent membership grants preserve both groups`() {
        rbac.createGroup(CreateGroupRequest("first-group"))
        rbac.createGroup(CreateGroupRequest("second-group"))
        val start = java.util.concurrent.CountDownLatch(1)
        java.util.concurrent.Executors.newFixedThreadPool(2).use { executor ->
            val tasks =
                listOf("first-group", "second-group").map { group ->
                    executor.submit {
                        start.await()
                        rbac.addMember(group, "analyst")
                    }
                }
            start.countDown()
            tasks.forEach { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        }
        assertThat(users.currentUserPrincipal("analyst")!!.groups).contains("first-group", "second-group")
    }

    private fun oidc(
        username: String,
        subject: String,
        issuer: String = "https://issuer.test",
        groups: Set<String> = emptySet(),
    ) = users.provisionOrUpdateOidcUser(username, username, null, groups, setOf("USER"), issuer, subject)

    @Test fun `external identity cannot claim a local username`() {
        val admin = users.currentUserPrincipal("admin")!!
        assertThrows<ExternalIdentityConflictException> { oidc("admin", "foreign-subject") }
        assertThat(users.currentUserPrincipal("admin")).isEqualTo(admin)
    }

    @Test fun `issuer and subject identify the account independently of display username`() {
        val first = oidc("alice", "subject-1")
        val renamed = oidc("new-name", "subject-1")
        assertThat(renamed.userId).isEqualTo(first.userId)
        assertThat(renamed.username).isEqualTo("alice")
        assertThrows<ExternalIdentityConflictException> { oidc("alice", "subject-2") }
        assertThrows<ExternalIdentityConflictException> { oidc("alice", "subject-1", "https://other.test") }
        assertThrows<IllegalArgumentException> { users.resetPassword("alice", "Password123!") }
        assertThat(users.authenticateLocal("alice", "Password123!")).isNull()
    }

    @Test fun `legacy external accounts require explicit linking and retain their ID`() {
        accounts.save(UserAccount("legacy", "Legacy", null, null, AuthProvider.OIDC, true, false, mutableSetOf("USER"), mutableSetOf()))
        val before = users.currentUserPrincipal("legacy")!!
        assertThrows<ExternalIdentityConflictException> { oidc("legacy", "subject") }
        users.linkExternalIdentity("legacy", AuthProvider.OIDC, "https://issuer.test", "subject")
        assertThat(oidc("legacy", "subject").userId).isEqualTo(before.userId)
    }

    @Test fun `external synchronization and administrative views share membership state`() {
        rbac.createGroup(CreateGroupRequest("external-readers"))
        oidc("alice", "subject", groups = setOf("external-readers"))
        assertThat(rbac.listGroups().single { it.id == "external-readers" }.members).containsExactly("alice")
        oidc("alice", "subject")
        assertThat(rbac.listGroups().single { it.id == "external-readers" }.members).isEmpty()
        rbac.addMember("external-readers", "alice")
        assertThat(users.currentUserPrincipal("alice")!!.groups).contains("external-readers")
        rbac.deleteGroup("external-readers")
        assertThat(users.currentUserPrincipal("alice")!!.groups).doesNotContain("external-readers")
    }
}
