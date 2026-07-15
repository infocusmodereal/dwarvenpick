package com.dwarvenpick.app.query

import org.springframework.stereotype.Service

/** Operates on metadata only after the caller has enforced execution access. */
@Service
class PersistedQueryResultAccessService(
    private val queryRuntimeRepository: QueryRuntimeRepository,
    private val resultLifecycleService: PersistedQueryResultLifecycleService,
    private val queryExecutionProperties: QueryExecutionProperties,
    private val pageTokenCodec: QueryResultPageTokenCodec,
) {
    fun getResults(
        metadata: PersistedQueryRuntimeMetadataRecord,
        request: QueryResultsRequest,
    ): QueryResultsResponse {
        if (metadata.status == QueryExecutionStatus.QUEUED || metadata.status == QueryExecutionStatus.RUNNING) {
            throw QueryResultsNotReadyException(
                "Query results are not ready yet. Current status: ${metadata.status.name}.",
            )
        }
        if (metadata.resultsExpired) {
            throw QueryResultsExpiredException("Result session expired. Re-run the query.")
        }
        if (metadata.status == QueryExecutionStatus.FAILED) {
            throw QueryResultsNotReadyException("Query failed and no result set is available.")
        }
        if (metadata.status == QueryExecutionStatus.CANCELED) {
            throw QueryResultsNotReadyException("Query was canceled before a complete result set was available.")
        }

        val resolvedPageSize =
            request.pageSize?.coerceIn(1, queryExecutionProperties.maxPageSize.coerceAtLeast(1))
                ?: queryExecutionProperties.defaultPageSize.coerceAtLeast(1)
        val startOffset = request.pageToken?.let { pageTokenCodec.parse(metadata.executionId, it) } ?: 0
        if (startOffset > metadata.rowCount) {
            throw QueryInvalidPageTokenException("pageToken offset is outside of available result rows.")
        }
        resultLifecycleService.touchResultSession(metadata.executionId)
        val rows = queryRuntimeRepository.fetchRows(metadata.executionId, startOffset, resolvedPageSize)
        val endOffset = startOffset + rows.size
        val nextPageToken =
            if (endOffset < metadata.rowCount) {
                pageTokenCodec.build(metadata.executionId, endOffset)
            } else {
                null
            }
        return QueryResultsResponse(
            executionId = metadata.executionId,
            status = metadata.status.name,
            columns = metadata.columns,
            rows = rows,
            pageSize = resolvedPageSize,
            nextPageToken = nextPageToken,
            rowLimitReached = metadata.rowLimitReached,
        )
    }

    fun prepareCsvExport(
        metadata: PersistedQueryRuntimeMetadataRecord,
        includeHeaders: Boolean,
        maxExportRows: Int,
    ): QueryCsvExportPayload {
        if (metadata.status == QueryExecutionStatus.QUEUED || metadata.status == QueryExecutionStatus.RUNNING) {
            throw QueryResultsNotReadyException(
                "Query results are not ready yet. Current status: ${metadata.status.name}.",
            )
        }
        if (metadata.resultsExpired) {
            throw QueryResultsExpiredException("Result session expired. Re-run the query.")
        }
        if (metadata.status == QueryExecutionStatus.FAILED) {
            throw QueryResultsNotReadyException("Query failed and cannot be exported.")
        }
        if (metadata.status == QueryExecutionStatus.CANCELED) {
            throw QueryResultsNotReadyException("Query was canceled and cannot be exported.")
        }

        val resolvedMaxExportRows = maxExportRows.coerceAtLeast(1)
        if (metadata.rowCount > resolvedMaxExportRows) {
            throw QueryExportLimitExceededException(
                "Export row limit exceeded (${metadata.rowCount} rows > $resolvedMaxExportRows allowed).",
            )
        }
        val resultLeaseId = resultLifecycleService.acquireExportLease(metadata.executionId)
        return QueryCsvExportPayload(
            executionId = metadata.executionId,
            resultLeaseId = resultLeaseId,
            datasourceId = metadata.datasourceId,
            includeHeaders = includeHeaders,
            rowCount = metadata.rowCount,
            columns = metadata.columns,
            rows =
                resultLifecycleService.protectExportRows(
                    executionId = metadata.executionId,
                    leaseId = resultLeaseId,
                    rows = queryRuntimeRepository.rowIterable(metadata.executionId),
                ),
        )
    }
}
