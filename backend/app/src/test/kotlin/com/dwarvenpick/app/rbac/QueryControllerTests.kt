package com.dwarvenpick.app.rbac

import com.dwarvenpick.app.auth.AuthAuditLogger
import com.dwarvenpick.app.auth.AuthProvider
import com.dwarvenpick.app.auth.AuthenticatedPrincipalResolver
import com.dwarvenpick.app.auth.AuthenticatedUserPrincipal
import com.dwarvenpick.app.auth.ErrorResponse
import com.dwarvenpick.app.controlplane.DatasourcePauseService
import com.dwarvenpick.app.datasource.ForbiddenNetworkTargetException
import com.dwarvenpick.app.query.QueryCapacityLimitException
import com.dwarvenpick.app.query.QueryExecutionManager
import com.dwarvenpick.app.query.QueryExecutionProperties
import com.dwarvenpick.app.query.QueryExecutionRequest
import com.dwarvenpick.app.query.QueryValidationRequest
import com.dwarvenpick.app.query.QueryValidationService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

class QueryControllerTests {
    private val rbacService = mock(RbacService::class.java)
    private val authAuditLogger = mock(AuthAuditLogger::class.java)
    private val authenticatedPrincipalResolver = mock(AuthenticatedPrincipalResolver::class.java)
    private val queryExecutionManager = mock(QueryExecutionManager::class.java)
    private val queryValidationService = mock(QueryValidationService::class.java)
    private val datasourcePauseService = mock(DatasourcePauseService::class.java)

    private val controller =
        QueryController(
            rbacService = rbacService,
            authAuditLogger = authAuditLogger,
            authenticatedPrincipalResolver = authenticatedPrincipalResolver,
            queryExecutionManager = queryExecutionManager,
            queryExecutionProperties = QueryExecutionProperties(),
            queryValidationService = queryValidationService,
            datasourcePauseService = datasourcePauseService,
            meterRegistry = SimpleMeterRegistry(),
        )

    @Test
    fun `validate returns bad request when network guard blocks datasource`() {
        val principal =
            AuthenticatedUserPrincipal(
                username = "ivan",
                displayName = "Ivan",
                email = null,
                provider = AuthProvider.LDAP,
                roles = emptySet(),
                groups = emptySet(),
            )
        val authentication = UsernamePasswordAuthenticationToken(principal, "unused")
        val request =
            QueryValidationRequest(
                datasourceId = "blocked-datasource",
                sql = "select 1",
            )
        val policy =
            QueryAccessPolicy(
                credentialProfile = "read-only",
                readOnly = true,
                maxRowsPerQuery = 5000,
                maxRuntimeSeconds = 300,
                concurrencyLimit = 5,
            )

        `when`(authenticatedPrincipalResolver.resolve(authentication)).thenReturn(principal)
        `when`(
            rbacService.resolveQueryAccessPolicy(
                principal = principal,
                datasourceId = "blocked-datasource",
                requestedCredentialProfile = null,
            ),
        ).thenReturn(policy)
        `when`(queryValidationService.validate(request = request, policy = policy))
            .thenThrow(ForbiddenNetworkTargetException("Datasource host is blocked by network guard policy."))

        val response = controller.validateQuery(request, authentication)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body)
            .isEqualTo(ErrorResponse("Datasource host is blocked by network guard policy."))
    }

    @Test
    fun `aggregate admission rejection returns retryable overload response`() {
        val principal =
            AuthenticatedUserPrincipal(
                username = "ivan",
                displayName = "Ivan",
                email = null,
                provider = AuthProvider.LDAP,
                roles = emptySet(),
                groups = emptySet(),
            )
        val authentication = UsernamePasswordAuthenticationToken(principal, "unused")
        val httpRequest = mock(HttpServletRequest::class.java)
        val request = QueryExecutionRequest(datasourceId = "busy-datasource", sql = "select 1")
        val policy =
            QueryAccessPolicy(
                credentialProfile = "read-only",
                readOnly = true,
                maxRowsPerQuery = 5000,
                maxRuntimeSeconds = 300,
                concurrencyLimit = 5,
            )

        `when`(authenticatedPrincipalResolver.resolve(authentication)).thenReturn(principal)
        `when`(httpRequest.remoteAddr).thenReturn("127.0.0.1")
        `when`(
            rbacService.resolveQueryAccessPolicy(
                principal = principal,
                datasourceId = request.datasourceId,
                requestedCredentialProfile = null,
            ),
        ).thenReturn(policy)
        `when`(
            queryExecutionManager.submitQuery(
                actor = principal.username,
                ipAddress = "127.0.0.1",
                request = request,
                policy = policy,
            ),
        ).thenThrow(QueryCapacityLimitException("Query service capacity is full. Retry later."))

        val response = controller.executeQuery(request, authentication, httpRequest)

        assertThat(response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(response.body).isEqualTo(ErrorResponse("Query service capacity is full. Retry later."))
    }
}
