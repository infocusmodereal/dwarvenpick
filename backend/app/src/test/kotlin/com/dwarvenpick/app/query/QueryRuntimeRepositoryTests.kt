package com.dwarvenpick.app.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant

@SpringBootTest
class QueryRuntimeRepositoryTests {
    @Autowired
    private lateinit var queryRuntimeRepository: QueryRuntimeRepository

    @Autowired
    private lateinit var namedParameterJdbcTemplate: NamedParameterJdbcTemplate

    @BeforeEach
    fun resetRuntime() {
        queryRuntimeRepository.clear()
    }

    @Test
    fun `metadata paths do not deserialize persisted result rows`() {
        val record = runtimeRecord(executionId = "metadata-only-exec")
        queryRuntimeRepository.save(record)
        namedParameterJdbcTemplate.update(
            """
            UPDATE query_runtime_executions
            SET rows_json = :rowsJson,
                columns_json = :columnsJson,
                cancel_requested = TRUE
            WHERE execution_id = :executionId
            """.trimIndent(),
            mapOf(
                "executionId" to record.executionId,
                "rowsJson" to "not-json",
                "columnsJson" to "not-json",
            ),
        )

        val metadata = queryRuntimeRepository.findMetadata(record.executionId)
        val activeMetadata =
            queryRuntimeRepository.listActiveMetadata(
                actor = record.actor,
                isSystemAdmin = false,
                datasourceId = null,
                actorFilter = null,
            )

        assertThat(metadata?.executionId).isEqualTo(record.executionId)
        assertThat(metadata?.rowCount).isEqualTo(1)
        assertThat(metadata?.columnCount).isEqualTo(1)
        assertThat(metadata?.cancelRequested).isTrue()
        assertThat(activeMetadata).extracting<String> { it.executionId }.containsExactly(record.executionId)

        queryRuntimeRepository.save(record.copy(message = "Still running.", cancelRequested = false))

        val savedMetadata = queryRuntimeRepository.findMetadata(record.executionId)
        val savedFullRecord = queryRuntimeRepository.find(record.executionId)
        assertThat(savedMetadata?.cancelRequested).isTrue()
        assertThat(savedFullRecord?.rows).containsExactly(listOf("value"))
    }

    private fun runtimeRecord(executionId: String): PersistedQueryRuntimeRecord {
        val submittedAt = Instant.now()
        return PersistedQueryRuntimeRecord(
            executionId = executionId,
            actor = "metadata-user",
            ipAddress = "127.0.0.1",
            datasourceId = "unit-test-datasource",
            credentialProfile = "read-only",
            justification = null,
            sql = "select 'value'",
            sqlRedacted = false,
            queryHash = "hash-$executionId",
            maxRowsPerQuery = 5000,
            maxRuntimeSeconds = 300,
            concurrencyLimit = 1,
            scriptStatementCount = 1,
            scriptStopOnError = true,
            scriptTransactionMode = ScriptTransactionMode.AUTOCOMMIT,
            scriptStatements = emptyList(),
            status = QueryExecutionStatus.RUNNING,
            message = "Query is running.",
            errorSummary = null,
            submittedAt = submittedAt,
            startedAt = submittedAt.plusMillis(10),
            completedAt = null,
            rowLimitReached = false,
            columns = listOf(QueryResultColumn(name = "result", jdbcType = "VARCHAR")),
            rows = listOf(listOf("value")),
            lastAccessedAt = submittedAt,
            resultsExpired = false,
            cancelRequested = false,
            ownerInstanceId = "test-instance",
            heartbeatAt = submittedAt,
        )
    }
}
