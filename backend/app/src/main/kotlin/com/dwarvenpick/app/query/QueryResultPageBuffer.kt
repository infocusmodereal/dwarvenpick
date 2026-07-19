package com.dwarvenpick.app.query

data class QueryResultPageSnapshot(
    val pageIndex: Int,
    val startRow: Int,
    val rows: List<List<String?>>,
    val logicalByteCount: Long,
) {
    val rowCount: Int = rows.size
}

data class QueryResultBufferReset(
    val pendingByteCount: Long,
)

/** Holds at most one result page while the repository persists completed pages. */
class QueryResultPageBuffer(
    private val pageSize: Int,
) {
    private var resultColumns: List<QueryResultColumn> = emptyList()
    private val pendingRows = mutableListOf<List<String?>>()
    private var pendingByteCount = 0L
    private var committedRowCount = 0
    private var committedByteCount = 0L
    private var nextPageIndex = 0
    private var inFlightSnapshot: QueryResultPageSnapshot? = null

    @Synchronized
    fun replaceColumns(columns: List<QueryResultColumn>) {
        check(inFlightSnapshot == null) { "Cannot replace result columns while a page is being persisted." }
        resultColumns = columns.toList()
    }

    @Synchronized
    fun columns(): List<QueryResultColumn> = resultColumns

    @Synchronized
    fun totalRowCount(): Int = committedRowCount + pendingRows.size

    @Synchronized
    fun totalLogicalByteCount(): Long = committedByteCount + pendingByteCount

    @Synchronized
    fun pendingLogicalByteCount(): Long = pendingByteCount

    @Synchronized
    fun pendingRowCount(): Int = pendingRows.size

    @Synchronized
    fun append(
        row: List<String?>,
        logicalByteCount: Long,
    ): QueryResultPageSnapshot? {
        check(inFlightSnapshot == null) { "Cannot append rows while a page is being persisted." }
        pendingRows.add(row.toList())
        pendingByteCount += logicalByteCount
        return if (pendingRows.size >= pageSize.coerceAtLeast(1)) snapshotPending() else null
    }

    @Synchronized
    fun snapshotPending(): QueryResultPageSnapshot? {
        if (pendingRows.isEmpty()) {
            return null
        }
        inFlightSnapshot?.let { return it }
        return QueryResultPageSnapshot(
            pageIndex = nextPageIndex,
            startRow = committedRowCount,
            rows = pendingRows.map { row -> row.toList() },
            logicalByteCount = pendingByteCount,
        ).also { snapshot -> inFlightSnapshot = snapshot }
    }

    @Synchronized
    fun acknowledge(snapshot: QueryResultPageSnapshot): Long {
        check(inFlightSnapshot === snapshot) { "Result page snapshot is no longer active." }
        check(pendingRows.size == snapshot.rowCount && pendingByteCount == snapshot.logicalByteCount) {
            "Pending result page changed before persistence acknowledgement."
        }
        pendingRows.clear()
        pendingByteCount = 0
        committedRowCount += snapshot.rowCount
        committedByteCount += snapshot.logicalByteCount
        nextPageIndex += 1
        inFlightSnapshot = null
        return snapshot.logicalByteCount
    }

    @Synchronized
    fun discardPending(): Long {
        val releasedBytes = pendingByteCount
        pendingRows.clear()
        pendingByteCount = 0
        inFlightSnapshot = null
        return releasedBytes
    }

    @Synchronized
    fun reset(): QueryResultBufferReset {
        val releasedBytes = pendingByteCount
        resultColumns = emptyList()
        pendingRows.clear()
        pendingByteCount = 0
        committedRowCount = 0
        committedByteCount = 0
        nextPageIndex = 0
        inFlightSnapshot = null
        return QueryResultBufferReset(releasedBytes)
    }
}
