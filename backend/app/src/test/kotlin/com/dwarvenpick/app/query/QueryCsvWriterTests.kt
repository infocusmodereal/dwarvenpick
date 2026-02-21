package com.dwarvenpick.app.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class QueryCsvWriterTests {
    @Test
    fun `writes csv with headers and escapes quotes commas newlines`() {
        val output = ByteArrayOutputStream()

        QueryCsvWriter.writeCsv(
            outputStream = output,
            columns =
                listOf(
                    QueryResultColumn(name = "name", jdbcType = "VARCHAR"),
                    QueryResultColumn(name = "note", jdbcType = "VARCHAR"),
                    QueryResultColumn(name = "description", jdbcType = "VARCHAR"),
                ),
            rows =
                listOf(
                    listOf("alice", "hello,world", "he said \"hi\""),
                    listOf("bob", null, "line1\nline2"),
                ),
            includeHeaders = true,
        )

        val csv = output.toString(StandardCharsets.UTF_8)
        val expected =
            "name,note,description\n" +
                "alice,\"hello,world\",\"he said \"\"hi\"\"\"\n" +
                "bob,,\"line1\nline2\"\n"
        assertThat(csv).isEqualTo(expected)
    }

    @Test
    fun `writes csv without headers and preserves unicode text`() {
        val output = ByteArrayOutputStream()

        QueryCsvWriter.writeCsv(
            outputStream = output,
            columns = listOf(QueryResultColumn(name = "text", jdbcType = "VARCHAR")),
            rows =
                listOf(
                    listOf("café"),
                    listOf("東京"),
                ),
            includeHeaders = false,
        )

        val csv = output.toString(StandardCharsets.UTF_8)
        assertThat(csv).isEqualTo("café\n東京\n")
    }
}
