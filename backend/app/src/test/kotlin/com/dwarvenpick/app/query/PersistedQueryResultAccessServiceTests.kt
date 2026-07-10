package com.dwarvenpick.app.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant

class PersistedQueryResultAccessServiceTests {
    private lateinit var queryRuntimeRepository: QueryRuntimeRepository
    private lateinit var pageTokenCodec: QueryResultPageTokenCodec
    private lateinit var service: PersistedQueryResultAccessService

    @BeforeEach
    fun setUp() {
        queryRuntimeRepository = mock(QueryRuntimeRepository::class.java)
        pageTokenCodec = QueryResultPageTokenCodec()
        service =
            PersistedQueryResultAccessService(
                queryRuntimeRepository = queryRuntimeRepository,
                queryExecutionProperties =
                    QueryExecutionProperties(
                        defaultPageSize = 2,
                        maxPageSize = 3,
                    ),
                pageTokenCodec = pageTokenCodec,
            )
    }

    @Test
    fun `persisted results use bounded pages and compatible tokens`() {
        val metadata = metadata(rowCount = 5)
        `when`(queryRuntimeRepository.fetchRows(metadata.executionId, 0, 2))
            .thenReturn(listOf(row("one"), row("two")))
        `when`(queryRuntimeRepository.fetchRows(metadata.executionId, 2, 3))
            .thenReturn(listOf(row("three"), row("four"), row("five")))

        val firstPage = service.getResults(metadata, QueryResultsRequest())
        val secondPage =
            service.getResults(
                metadata,
                QueryResultsRequest(pageToken = firstPage.nextPageToken, pageSize = 99),
            )

        assertThat(firstPage.pageSize).isEqualTo(2)
        assertThat(firstPage.rows).containsExactly(row("one"), row("two"))
        assertThat(pageTokenCodec.parse(metadata.executionId, firstPage.nextPageToken!!)).isEqualTo(2)
        assertThat(secondPage.pageSize).isEqualTo(3)
        assertThat(secondPage.rows).containsExactly(row("three"), row("four"), row("five"))
        assertThat(secondPage.nextPageToken).isNull()
        assertLastAccessedWasUpdated(metadata.executionId)
    }

    @Test
    fun `offset equal to row count returns an empty terminal page`() {
        val metadata = metadata(rowCount = 5)
        val token = pageTokenCodec.build(metadata.executionId, 5)
        `when`(queryRuntimeRepository.fetchRows(metadata.executionId, 5, 2)).thenReturn(emptyList())

        val response = service.getResults(metadata, QueryResultsRequest(pageToken = token))

        assertThat(response.rows).isEmpty()
        assertThat(response.nextPageToken).isNull()
    }

    @Test
    fun `invalid mismatched and out of range page tokens are rejected`() {
        val metadata = metadata(rowCount = 2)

        assertThrows<QueryInvalidPageTokenException> {
            service.getResults(metadata, QueryResultsRequest(pageToken = "not-base64"))
        }
        assertThrows<QueryInvalidPageTokenException> {
            service.getResults(
                metadata,
                QueryResultsRequest(pageToken = pageTokenCodec.build("different-execution", 1)),
            )
        }
        assertThrows<QueryInvalidPageTokenException> {
            service.getResults(
                metadata,
                QueryResultsRequest(pageToken = pageTokenCodec.build(metadata.executionId, 3)),
            )
        }
        verify(queryRuntimeRepository, never()).fetchRows(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(),
        )
    }

    @Test
    fun `nonterminal failed canceled and expired results are rejected before IO`() {
        listOf(QueryExecutionStatus.QUEUED, QueryExecutionStatus.RUNNING, QueryExecutionStatus.FAILED, QueryExecutionStatus.CANCELED)
            .forEach { status ->
                assertThrows<QueryResultsNotReadyException> {
                    service.getResults(metadata(status = status), QueryResultsRequest())
                }
            }
        assertThrows<QueryResultsExpiredException> {
            service.getResults(metadata(resultsExpired = true), QueryResultsRequest())
        }
        verify(queryRuntimeRepository, never()).fetchRows(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(),
        )
    }

    @Test
    fun `persisted export preserves lazy rows and enforces row limit`() {
        val metadata = metadata(rowCount = 2)
        val rows = listOf(row("one"), row("two"))
        `when`(queryRuntimeRepository.rowIterable(metadata.executionId)).thenReturn(rows)

        val payload = service.prepareCsvExport(metadata, includeHeaders = false, maxExportRows = 2)

        assertThat(payload.executionId).isEqualTo(metadata.executionId)
        assertThat(payload.datasourceId).isEqualTo(metadata.datasourceId)
        assertThat(payload.includeHeaders).isFalse()
        assertThat(payload.rows).isSameAs(rows)
        assertThrows<QueryExportLimitExceededException> {
            service.prepareCsvExport(metadata.copy(rowCount = 3), includeHeaders = true, maxExportRows = 2)
        }
        assertLastAccessedWasUpdated(metadata.executionId)
    }

    @Test
    fun `expired and incomplete executions cannot be exported`() {
        listOf(QueryExecutionStatus.QUEUED, QueryExecutionStatus.RUNNING, QueryExecutionStatus.FAILED, QueryExecutionStatus.CANCELED)
            .forEach { status ->
                assertThrows<QueryResultsNotReadyException> {
                    service.prepareCsvExport(metadata(status = status), includeHeaders = true, maxExportRows = 10)
                }
            }
        assertThrows<QueryResultsExpiredException> {
            service.prepareCsvExport(metadata(resultsExpired = true), includeHeaders = true, maxExportRows = 10)
        }
        verify(queryRuntimeRepository, never()).rowIterable(org.mockito.ArgumentMatchers.anyString())
    }

    private fun metadata(
        status: QueryExecutionStatus = QueryExecutionStatus.SUCCEEDED,
        rowCount: Int = 2,
        resultsExpired: Boolean = false,
    ): PersistedQueryRuntimeMetadataRecord {
        val submittedAt = Instant.parse("2026-07-10T12:00:00Z")
        return PersistedQueryRuntimeMetadataRecord(
            executionId = "persisted-execution",
            actor = "result-user",
            ipAddress = "127.0.0.1",
            datasourceId = "postgresql-core",
            credentialProfile = "read-only",
            justification = null,
            sql = "select value",
            sqlRedacted = false,
            queryHash = "hash",
            maxRowsPerQuery = 100,
            maxRuntimeSeconds = 30,
            concurrencyLimit = 1,
            scriptStatementCount = 1,
            scriptStopOnError = true,
            scriptTransactionMode = ScriptTransactionMode.AUTOCOMMIT,
            scriptStatements = emptyList(),
            status = status,
            message = "Query completed.",
            errorSummary = null,
            submittedAt = submittedAt,
            startedAt = submittedAt.plusSeconds(1),
            completedAt = submittedAt.plusSeconds(2),
            rowCount = rowCount,
            columnCount = 1,
            rowLimitReached = false,
            columns = listOf(QueryResultColumn(name = "value", jdbcType = "VARCHAR")),
            lastAccessedAt = submittedAt.plusSeconds(2),
            resultsExpired = resultsExpired,
            cancelRequested = false,
            ownerInstanceId = "test-instance",
            heartbeatAt = submittedAt.plusSeconds(2),
        )
    }

    private fun row(value: String): List<String?> = listOf(value)

    private fun assertLastAccessedWasUpdated(executionId: String) {
        val touchedExecutionIds =
            mockingDetails(queryRuntimeRepository)
                .invocations
                .filter { invocation -> invocation.method.name == "updateLastAccessed" }
                .map { invocation -> invocation.arguments[0] }
        assertThat(touchedExecutionIds).contains(executionId)
    }
}
