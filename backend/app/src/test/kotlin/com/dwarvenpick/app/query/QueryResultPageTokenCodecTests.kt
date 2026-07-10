package com.dwarvenpick.app.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QueryResultPageTokenCodecTests {
    private val codec = QueryResultPageTokenCodec()

    @Test
    fun `token wire format remains url safe and unpadded`() {
        val token = codec.build("execution-1", 42)

        assertThat(token).isEqualTo("ZXhlY3V0aW9uLTE6NDI")
        assertThat(codec.parse("execution-1", token)).isEqualTo(42)
    }

    @Test
    fun `negative token offsets remain invalid`() {
        val negativeOffsetToken = "ZXhlY3V0aW9uLTE6LTE"

        assertThrows<QueryInvalidPageTokenException> {
            codec.parse("execution-1", negativeOffsetToken)
        }
    }
}
