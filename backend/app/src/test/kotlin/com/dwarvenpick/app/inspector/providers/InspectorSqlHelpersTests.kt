package com.dwarvenpick.app.inspector.providers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.DriverManager

class InspectorSqlHelpersTests {
    @Test
    fun `runTableQuery caps rows and long cell values`() {
        DriverManager.getConnection("jdbc:h2:mem:inspector-caps;DB_CLOSE_DELAY=-1").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE sample (id INT PRIMARY KEY, note VARCHAR(128))")
            }
            connection
                .prepareStatement("INSERT INTO sample (id, note) VALUES (?, ?)")
                .use { statement ->
                    repeat(6) { index ->
                        statement.setInt(1, index + 1)
                        statement.setString(2, "0123456789abcdef")
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }

            val table =
                runTableQuery(
                    connection = connection,
                    sql = "SELECT id, note FROM sample ORDER BY id",
                    rowLimit = 3,
                    cellCharLimit = 8,
                )

            assertThat(table.columns).containsExactly("ID", "NOTE")
            assertThat(table.rows).hasSize(3)
            assertThat(table.rowLimit).isEqualTo(3)
            assertThat(table.truncated).isTrue()
            assertThat(table.cellLimit).isEqualTo(8)
            assertThat(table.cellsTruncated).isTrue()
            assertThat(table.rows.first()[1]).isEqualTo(
                "01234567\n\n[truncated to 8 characters]",
            )
        }
    }

    @Test
    fun `runKeyValueQuery caps long values`() {
        DriverManager.getConnection("jdbc:h2:mem:inspector-key-values;DB_CLOSE_DELAY=-1").use { connection ->
            val keyValues =
                runKeyValueQuery(
                    connection = connection,
                    sql = "SELECT '0123456789abcdef' AS note",
                    valueCharLimit = 8,
                )

            assertThat(keyValues).hasSize(1)
            assertThat(keyValues.first().key).isEqualTo("NOTE")
            assertThat(keyValues.first().value).isEqualTo(
                "01234567\n\n[truncated to 8 characters]",
            )
        }
    }
}
