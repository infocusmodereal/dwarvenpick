package com.dwarvenpick.app.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant

@SpringBootTest(
    properties = [
        "dwarvenpick.query.result-chunk-rows=2",
    ],
)
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
                cancel_requested = TRUE
            WHERE execution_id = :executionId
            """.trimIndent(),
            mapOf(
                "executionId" to record.executionId,
                "rowsJson" to "not-json",
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

    @Test
    fun `result rows are persisted and fetched in bounded pages`() {
        val rows = (1..5).map { index -> listOf("value-$index") }
        val record =
            runtimeRecord(executionId = "paged-exec")
                .copy(
                    status = QueryExecutionStatus.SUCCEEDED,
                    completedAt = Instant.now(),
                    rows = rows,
                )

        queryRuntimeRepository.save(record)

        val storedRowsJson =
            namedParameterJdbcTemplate.queryForObject(
                "SELECT rows_json FROM query_runtime_executions WHERE execution_id = :executionId",
                mapOf("executionId" to record.executionId),
                String::class.java,
            )
        assertThat(storedRowsJson).isEqualTo("[]")
        assertThat(queryRuntimeRepository.countResultPages(record.executionId)).isEqualTo(3)
        assertThat(queryRuntimeRepository.findMetadata(record.executionId)?.rowCount).isEqualTo(5)

        namedParameterJdbcTemplate.update(
            """
            UPDATE query_runtime_result_pages
            SET rows_json = :rowsJson
            WHERE execution_id = :executionId
              AND page_index = 2
            """.trimIndent(),
            mapOf("executionId" to record.executionId, "rowsJson" to "not-json"),
        )

        assertThat(queryRuntimeRepository.fetchRows(record.executionId, startOffset = 1, limit = 2))
            .containsExactly(listOf("value-2"), listOf("value-3"))
        assertThat(queryRuntimeRepository.findMetadata(record.executionId)?.rowCount).isEqualTo(5)
    }

    @Test
    fun `expiring results clears result pages`() {
        val record =
            runtimeRecord(executionId = "expire-pages-exec")
                .copy(rows = (1..3).map { index -> listOf("value-$index") })
        queryRuntimeRepository.save(record)

        queryRuntimeRepository.expireResults(record.executionId, "expired")

        assertThat(queryRuntimeRepository.countResultPages(record.executionId)).isZero()
        val metadata = queryRuntimeRepository.findMetadata(record.executionId)
        assertThat(metadata?.resultsExpired).isTrue()
        assertThat(metadata?.rowCount).isZero()
        assertThat(metadata?.columnCount).isZero()
        assertThat(metadata?.columns).isEmpty()
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
