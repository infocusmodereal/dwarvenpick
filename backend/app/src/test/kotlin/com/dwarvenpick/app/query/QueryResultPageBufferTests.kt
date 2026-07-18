package com.dwarvenpick.app.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QueryResultPageBufferTests {
    @Test
    fun `completed pages release pending storage while cumulative limits remain`() {
        val buffer = QueryResultPageBuffer(pageSize = 2)
        buffer.replaceColumns(listOf(QueryResultColumn("value", "VARCHAR")))

        assertThat(buffer.append(listOf("one"), 3)).isNull()
        val firstPage = requireNotNull(buffer.append(listOf("two"), 3))

        assertThat(firstPage.pageIndex).isZero()
        assertThat(firstPage.startRow).isZero()
        assertThat(firstPage.rows).containsExactly(listOf("one"), listOf("two"))
        assertThat(buffer.pendingLogicalByteCount()).isEqualTo(6)
        assertThat(buffer.acknowledge(firstPage)).isEqualTo(6)
        assertThat(buffer.pendingLogicalByteCount()).isZero()
        assertThat(buffer.totalRowCount()).isEqualTo(2)
        assertThat(buffer.totalLogicalByteCount()).isEqualTo(6)

        assertThat(buffer.append(listOf("three"), 5)).isNull()
        val finalPage = requireNotNull(buffer.snapshotPending())
        assertThat(finalPage.pageIndex).isEqualTo(1)
        assertThat(finalPage.startRow).isEqualTo(2)
        assertThat(buffer.acknowledge(finalPage)).isEqualTo(5)
        assertThat(buffer.totalRowCount()).isEqualTo(3)
        assertThat(buffer.totalLogicalByteCount()).isEqualTo(11)
    }

    @Test
    fun `reset and duplicate acknowledgement release pending storage exactly once`() {
        val buffer = QueryResultPageBuffer(pageSize = 2)
        buffer.append(listOf("one"), 3)
        val snapshot = requireNotNull(buffer.snapshotPending())

        assertThat(buffer.reset().pendingByteCount).isEqualTo(3)
        assertThat(buffer.reset().pendingByteCount).isZero()
        assertThrows<IllegalStateException> { buffer.acknowledge(snapshot) }
    }

    @Test
    fun `large bounded result never retains more than one page`() {
        val buffer = QueryResultPageBuffer(pageSize = 50)
        var largestPendingRows = 0

        repeat(1_000) { index ->
            val page = buffer.append(listOf(index.toString()), 8)
            largestPendingRows = maxOf(largestPendingRows, buffer.pendingRowCount())
            if (page != null) {
                buffer.acknowledge(page)
            }
        }

        assertThat(largestPendingRows).isLessThanOrEqualTo(50)
        assertThat(buffer.pendingLogicalByteCount()).isZero()
        assertThat(buffer.totalRowCount()).isEqualTo(1_000)
        assertThat(buffer.totalLogicalByteCount()).isEqualTo(8_000)
    }
}
