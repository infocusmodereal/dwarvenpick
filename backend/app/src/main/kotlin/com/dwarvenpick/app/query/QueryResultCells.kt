package com.dwarvenpick.app.query

import java.io.InputStream
import java.io.PushbackReader
import java.io.Reader
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Types
import java.util.Locale

internal data class BufferedCellValue(
    val value: String?,
    val byteSize: Long,
    val truncated: Boolean,
)

internal object QueryResultCells {
    private const val RESULT_TRUNCATION_SUFFIX = "..."
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    fun read(
        resultSet: ResultSet,
        metadata: ResultSetMetaData,
        index: Int,
        maxCellBytes: Int,
    ): BufferedCellValue {
        val jdbcType = runCatching { metadata.getColumnType(index) }.getOrDefault(Types.OTHER)
        val jdbcTypeName =
            runCatching { metadata.getColumnTypeName(index) }
                .getOrNull()
                ?.lowercase(Locale.ROOT)
                .orEmpty()

        if (isCharacterLikeColumn(jdbcType, jdbcTypeName)) {
            val reader =
                runCatching {
                    if (
                        jdbcType == Types.NCLOB ||
                        jdbcType == Types.NCHAR ||
                        jdbcType == Types.NVARCHAR ||
                        jdbcType == Types.LONGNVARCHAR
                    ) {
                        resultSet.getNCharacterStream(index)
                    } else {
                        resultSet.getCharacterStream(index)
                    }
                }.getOrNull()
            if (reader != null) {
                return reader.use { toBufferedCellValue(it, maxCellBytes) }
            }
            if (resultSet.wasNull()) {
                return nullBufferedCellValue()
            }
        }

        if (isBinaryLikeColumn(jdbcType)) {
            val stream = runCatching { resultSet.getBinaryStream(index) }.getOrNull()
            if (stream != null) {
                return stream.use { toBufferedBinaryCellValue(it, maxCellBytes) }
            }
            if (resultSet.wasNull()) {
                return nullBufferedCellValue()
            }
        }

        return encode(resultSet.getObject(index), maxCellBytes)
    }

    fun encode(
        value: Any?,
        maxCellBytes: Int,
    ): BufferedCellValue =
        when (value) {
            null -> nullBufferedCellValue()
            is ByteArray -> value.inputStream().use { stream -> toBufferedBinaryCellValue(stream, maxCellBytes) }
            is java.sql.Clob -> value.characterStream.use { reader -> toBufferedCellValue(reader, maxCellBytes) }
            is java.sql.NClob -> value.characterStream.use { reader -> toBufferedCellValue(reader, maxCellBytes) }
            is java.sql.SQLXML -> value.characterStream.use { reader -> toBufferedCellValue(reader, maxCellBytes) }
            else -> text(value.toString(), maxCellBytes)
        }

    private fun toBufferedCellValue(
        reader: Reader,
        maxCellBytes: Int,
    ): BufferedCellValue {
        val input = PushbackReader(reader.buffered(), 1)
        val builder = StringBuilder()
        var size = 0
        while (true) {
            val first = input.read()
            if (first == -1) {
                return BufferedCellValue(builder.toString(), size.toLong(), truncated = false)
            }
            val codePoint =
                if (Character.isHighSurrogate(first.toChar())) {
                    val next = input.read()
                    if (next >= 0 && Character.isLowSurrogate(next.toChar())) {
                        Character.toCodePoint(first.toChar(), next.toChar())
                    } else {
                        if (next >= 0) input.unread(next)
                        0xfffd
                    }
                } else if (Character.isLowSurrogate(first.toChar())) {
                    0xfffd
                } else {
                    first
                }
            val bytes = utf8Bytes(codePoint)
            if (bytes > maxCellBytes - size) break
            builder.appendCodePoint(codePoint)
            size += bytes
        }

        val suffix = RESULT_TRUNCATION_SUFFIX.take(maxCellBytes)
        while (size > maxCellBytes - suffix.length) {
            val codePoint = Character.codePointBefore(builder, builder.length)
            builder.setLength(builder.length - Character.charCount(codePoint))
            size -= utf8Bytes(codePoint)
        }
        return BufferedCellValue(builder.append(suffix).toString(), (size + suffix.length).toLong(), truncated = true)
    }

    private fun utf8Bytes(codePoint: Int): Int =
        when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }

    fun text(
        text: String,
        maxCellBytes: Int,
    ): BufferedCellValue = text.reader().use { reader -> toBufferedCellValue(reader, maxCellBytes) }

    private fun toBufferedBinaryCellValue(
        inputStream: InputStream,
        maxCellBytes: Int,
    ): BufferedCellValue {
        val bytes = inputStream.readNBytes(maxCellBytes / 2 + 1)
        val truncated = bytes.size > maxCellBytes / 2
        val suffix = if (truncated) RESULT_TRUNCATION_SUFFIX.take(maxCellBytes) else ""
        val visibleLength = minOf(bytes.size, (maxCellBytes - suffix.length) / 2)
        val builderCapacity =
            ((visibleLength.toLong() * 2L) + if (truncated) suffix.length.toLong() else 0L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        val builder = StringBuilder(builderCapacity)
        for (index in 0 until visibleLength) {
            val value = bytes[index].toInt() and 0xff
            builder.append(HEX_DIGITS[value ushr 4])
            builder.append(HEX_DIGITS[value and 0x0f])
        }
        if (truncated) {
            builder.append(suffix)
        }
        val result = builder.toString()

        return BufferedCellValue(
            value = result,
            byteSize = result.length.toLong(),
            truncated = truncated,
        )
    }

    private fun nullBufferedCellValue(): BufferedCellValue = BufferedCellValue(value = null, byteSize = 0, truncated = false)

    private fun isCharacterLikeColumn(
        jdbcType: Int,
        jdbcTypeName: String,
    ): Boolean =
        jdbcType in
            setOf(
                Types.CHAR,
                Types.VARCHAR,
                Types.LONGVARCHAR,
                Types.NCHAR,
                Types.NVARCHAR,
                Types.LONGNVARCHAR,
                Types.CLOB,
                Types.NCLOB,
                Types.SQLXML,
            ) ||
            jdbcTypeName.contains("text") ||
            jdbcTypeName.contains("json") ||
            jdbcTypeName.contains("xml")

    private fun isBinaryLikeColumn(jdbcType: Int): Boolean =
        jdbcType in setOf(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB)
}
