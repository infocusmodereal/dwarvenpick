package com.dwarvenpick.app

import com.dwarvenpick.app.auth.UserAccountService
import com.dwarvenpick.app.resource.CreateResourceRequest
import com.dwarvenpick.app.resource.ResourceRevisionConflictException
import com.dwarvenpick.app.resource.ResourceService
import com.dwarvenpick.app.resource.UpdateResourceContentRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
open class ResourceRevisionIntegrityTests {
    @Autowired private lateinit var resources: ResourceService

    @Autowired private lateinit var users: UserAccountService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test fun `concurrent editors cannot commit the same revision`() {
        val principal = users.currentUserPrincipal("admin")!!
        val resource = resources.createResource(principal, CreateResourceRequest(title = "concurrent", sql = "select 1"))
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val results =
                (2..3).map { value ->
                    executor.submit<Boolean> {
                        start.await()
                        try {
                            resources.updateContent(
                                principal,
                                resource.resourceId,
                                UpdateResourceContentRequest(expectedRevision = 1, title = "concurrent", sql = "select $value"),
                            )
                            true
                        } catch (_: ResourceRevisionConflictException) {
                            false
                        }
                    }
                }
            start.countDown()
            assertThat(results.map { it.get(10, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(true, false)
        }
        assertThat(resources.getResource(principal, resource.resourceId).currentRevision).isEqualTo(2)
        assertThat(resources.listVersions(principal, resource.resourceId).map { it.revision }).containsExactly(2, 1)
    }

    @Test fun `version failure rolls back the script update`() {
        val principal = users.currentUserPrincipal("admin")!!
        val resource = resources.createResource(principal, CreateResourceRequest(title = "rollback", sql = "select 1"))
        jdbc.update("UPDATE resource_script_versions SET revision = 2 WHERE resource_id = ?", resource.resourceId)
        org.junit.jupiter.api.assertThrows<org.springframework.dao.DataIntegrityViolationException> {
            resources.updateContent(
                principal,
                resource.resourceId,
                UpdateResourceContentRequest(expectedRevision = 1, title = "changed", sql = "select 2"),
            )
        }
        val unchanged = resources.getResource(principal, resource.resourceId)
        assertThat(unchanged.currentRevision).isEqualTo(1)
        assertThat(unchanged.sql).isEqualTo("select 1")
    }
}
