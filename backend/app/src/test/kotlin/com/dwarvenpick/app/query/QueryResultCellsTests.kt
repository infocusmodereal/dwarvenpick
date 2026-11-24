package com.dwarvenpick.app.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QueryResultCellsTests {
    @Test
    fun `a complete value may use its entire byte allowance`() {
        assertThat(QueryResultCells.text("12345678", 8)).isEqualTo(BufferedCellValue("12345678", 8, false))
        assertThat(QueryResultCells.encode(byteArrayOf(0x12, 0x34), 4)).isEqualTo(BufferedCellValue("1234", 4, false))
    }

    @Test
    fun `truncation respects supplementary code points and the UTF-8 byte limit`() {
        val cell = QueryResultCells.text("😀😀😀", 8)
        assertThat(cell).isEqualTo(BufferedCellValue("😀...", 7, true))
        assertThat(cell.value!!.toByteArray(Charsets.UTF_8)).hasSize(7)
    }
}
