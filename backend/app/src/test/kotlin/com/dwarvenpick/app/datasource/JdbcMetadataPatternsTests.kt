package com.dwarvenpick.app.datasource

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.DriverManager

class JdbcMetadataPatternsTests {
    @Test
    fun `literal lookup does not include columns from another matching table`() {
        DriverManager.getConnection("jdbc:h2:mem:metadata-patterns").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE ORDER_ITEMS (EXPECTED INT)")
                statement.execute("CREATE TABLE ORDERXITEMS (UNRELATED INT)")
            }
            val metadata = connection.metaData
            val columns =
                metadata.getColumns(null, "PUBLIC", metadata.literalSearchPattern("ORDER_ITEMS"), "%").use { rows ->
                    buildList {
                        while (rows.next()) add(rows.getString("COLUMN_NAME"))
                    }
                }
            assertThat(columns).containsExactly("EXPECTED")
        }
    }
}
