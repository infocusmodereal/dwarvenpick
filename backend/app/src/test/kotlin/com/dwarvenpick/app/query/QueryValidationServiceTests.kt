package com.dwarvenpick.app.query

import com.dwarvenpick.app.datasource.DatasourcePoolManager
import com.dwarvenpick.app.rbac.QueryAccessPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class QueryValidationServiceTests {
    private val datasourcePoolManager = mock(DatasourcePoolManager::class.java)
    private val service = QueryValidationService(datasourcePoolManager)

    @Test
    fun `read only validation rejects writes in later script statements before opening a connection`() {
        val response =
            service.validate(
                request =
                    QueryValidationRequest(
                        datasourceId = "orders",
                        sql = "SELECT 1; UPDATE orders SET status = 'done'",
                    ),
                policy = readOnlyPolicy(),
            )

        assertThat(response.valid).isFalse()
        assertThat(response.message).contains("Read-only mode is enabled")
        verifyNoInteractions(datasourcePoolManager)
    }

    @Test
    fun `validation rejects explain analyze before opening a connection`() {
        val response =
            service.validate(
                request =
                    QueryValidationRequest(
                        datasourceId = "orders",
                        sql = "EXPLAIN ANALYZE SELECT * FROM orders",
                    ),
                policy = writePolicy(),
            )

        assertThat(response.valid).isFalse()
        assertThat(response.message).contains("Validation does not run EXPLAIN ANALYZE")
        verifyNoInteractions(datasourcePoolManager)
    }

    @Test
    fun `validation rejects explain wrapped writes before opening a connection`() {
        val response =
            service.validate(
                request =
                    QueryValidationRequest(
                        datasourceId = "orders",
                        sql = "EXPLAIN UPDATE orders SET status = 'done'",
                    ),
                policy = writePolicy(),
            )

        assertThat(response.valid).isFalse()
        assertThat(response.message).contains("EXPLAIN of write statements")
        verifyNoInteractions(datasourcePoolManager)
    }

    private fun readOnlyPolicy(): QueryAccessPolicy =
        QueryAccessPolicy(
            credentialProfile = "read-only",
            readOnly = true,
            maxRowsPerQuery = 5000,
            maxRuntimeSeconds = 300,
            concurrencyLimit = 5,
        )

    private fun writePolicy(): QueryAccessPolicy =
        readOnlyPolicy().copy(
            credentialProfile = "read-write",
            readOnly = false,
        )
}
