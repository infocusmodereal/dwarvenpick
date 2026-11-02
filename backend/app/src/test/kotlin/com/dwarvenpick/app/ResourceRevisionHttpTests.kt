package com.dwarvenpick.app

import com.dwarvenpick.app.auth.UserAccountService
import com.dwarvenpick.app.resource.CreateResourceRequest
import com.dwarvenpick.app.resource.ResourceService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class ResourceRevisionHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var resources: ResourceService

    @Autowired private lateinit var users: UserAccountService

    @Test fun `HTTP edits require revision and stale edits return conflict`() {
        val resource =
            resources.createResource(
                users.currentUserPrincipal("admin")!!,
                CreateResourceRequest(title = "http-revision", sql = "select 1"),
            )

        fun save(body: String) =
            mvc.perform(
                patch("/api/resources/${resource.resourceId}/content")
                    .with(user("admin").roles("SYSTEM_ADMIN"))
                    .with(csrf())
                    .contentType("application/json")
                    .content(body),
            )
        save("""{"title":"http-revision","sql":"select 2"}""").andExpect(status().isPreconditionRequired)
        val edit = """{"title":"http-revision","sql":"select 2","expectedRevision":1}"""
        save(edit).andExpect(status().isOk)
        save(edit).andExpect(status().isConflict)
    }
}
