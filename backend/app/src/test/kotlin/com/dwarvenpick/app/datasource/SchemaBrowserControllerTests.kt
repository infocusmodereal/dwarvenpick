package com.dwarvenpick.app.datasource

import com.dwarvenpick.app.auth.AuthenticatedPrincipalResolver
import com.dwarvenpick.app.auth.AuthenticatedUserPrincipal
import com.dwarvenpick.app.rbac.DatasourceController
import com.dwarvenpick.app.rbac.EffectiveDatasourcePolicyService
import com.dwarvenpick.app.rbac.QueryAccessPolicy
import com.dwarvenpick.app.rbac.RbacService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class SchemaBrowserControllerTests {
    private val rbac = mock(RbacService::class.java)
    private val resolver = mock(AuthenticatedPrincipalResolver::class.java)
    private val principal = mock(AuthenticatedUserPrincipal::class.java)
    private val authentication = UsernamePasswordAuthenticationToken("reader", null)
    private val pools = mock(DatasourcePoolManager::class.java)
    private val properties = SchemaBrowserProperties()
    private val service = SchemaBrowserService(pools, properties)

    @Test
    fun `denied streaming request never borrows a connection`() {
        SchemaBrowserStreamService(service, jacksonObjectMapper(), properties).use { streams ->
            `when`(resolver.resolve(authentication)).thenReturn(principal)
            val mvc = mvc(streams)
            mvc
                .perform(get("/api/datasources/trino/schema-browser?stream=true").principal(authentication))
                .andExpect(status().isForbidden)
            verifyNoInteractions(pools)
        }
    }

    @Test
    fun `streaming route uses MVC async handling and returns a parseable error document`() {
        SchemaBrowserStreamService(service, jacksonObjectMapper(), properties).use { streams ->
            `when`(resolver.resolve(authentication)).thenReturn(principal)
            `when`(rbac.canUserQuery(principal, "trino")).thenReturn(true)
            `when`(rbac.resolveQueryAccessPolicy(principal, "trino"))
                .thenReturn(QueryAccessPolicy("reader", DatasourceEngine.TRINO, true, 100, 10, 1))
            `when`(pools.openConnection("trino", "reader")).thenThrow(IllegalStateException("private driver failure"))
            val mvc = mvc(streams)
            val result =
                mvc
                    .perform(get("/api/datasources/trino/schema-browser?stream=true").principal(authentication))
                    .andExpect(request().asyncStarted())
                    .andReturn()
            mvc
                .perform(asyncDispatch(result))
                .andExpect(status().isOk)
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error").value("Unable to load schema browser data right now."))
        }
    }

    private fun mvc(streams: SchemaBrowserStreamService) =
        MockMvcBuilders
            .standaloneSetup(
                DatasourceController(rbac, mock(EffectiveDatasourcePolicyService::class.java), service, streams, resolver),
            ).build()
}
