package com.dwarvenpick.app.query

import com.dwarvenpick.app.datasource.ConnectionSpec
import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.datasource.DatasourcePoolManager
import com.dwarvenpick.app.datasource.PoolSettings
import com.dwarvenpick.app.datasource.QueryConnectionHandle
import com.dwarvenpick.app.rbac.QueryAccessPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.sql.Connection
import java.sql.Statement

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

    @Test
    fun `starrocks validation applies default schema before explain`() {
        val connection = mock(Connection::class.java)
        val schemaStatement = mock(Statement::class.java)
        val explainStatement = mock(Statement::class.java)
        `when`(connection.createStatement()).thenReturn(schemaStatement, explainStatement)
        `when`(
            datasourcePoolManager.openConnection(
                datasourceId = "starrocks-prod-adhoc",
                credentialProfile = "read-only",
            ),
        ).thenReturn(
            QueryConnectionHandle(
                spec =
                    connectionSpec(
                        datasourceId = "starrocks-prod-adhoc",
                        engine = DatasourceEngine.STARROCKS,
                    ),
                connection = connection,
            ),
        )

        val response =
            service.validate(
                request =
                    QueryValidationRequest(
                        datasourceId = "starrocks-prod-adhoc",
                        sql = "select * from adUnits limit 50",
                        defaultSchema = "Viper2",
                    ),
                policy = readOnlyPolicy(),
            )

        assertThat(response.valid).isTrue()
        verify(schemaStatement).execute("USE `default_catalog`.`Viper2`")
        verify(explainStatement).execute("EXPLAIN select * from adUnits limit 50")
        verify(connection).close()
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

    private fun connectionSpec(
        datasourceId: String,
        engine: DatasourceEngine,
    ): ConnectionSpec =
        ConnectionSpec(
            datasourceId = datasourceId,
            datasourceName = "Test datasource",
            credentialProfile = "read-only",
            engine = engine,
            driverId = "test-driver",
            driverClass = "com.mysql.cj.jdbc.Driver",
            driverSource = "built-in",
            jdbcUrl = "jdbc:test://localhost",
            username = "test",
            password = "test",
            pool = PoolSettings(),
        )
}
