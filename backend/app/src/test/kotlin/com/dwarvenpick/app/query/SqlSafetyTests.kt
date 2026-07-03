package com.dwarvenpick.app.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SqlSafetyTests {
    @Test
    fun `read only guard rejects executing explain and explain wrapped writes`() {
        val blockedSql =
            listOf(
                "EXPLAIN ANALYZE SELECT * FROM orders",
                "EXPLAIN ANALYZE UPDATE orders SET status = 'done'",
                "EXPLAIN ANALYZE DELETE FROM orders WHERE id = 1",
                "EXPLAIN ANALYZE INSERT INTO orders(id) VALUES (1)",
                "EXPLAIN UPDATE orders SET status = 'done'",
                "EXPLAIN DELETE FROM orders WHERE id = 1",
                "EXPLAIN INSERT INTO orders(id) VALUES (1)",
                "EXPLAIN (ANALYZE, FORMAT JSON) SELECT * FROM orders",
            )

        blockedSql.forEach { sql ->
            assertThat(SqlSafety.isReadOnlySql(sql))
                .describedAs(sql)
                .isFalse()
        }
    }

    @Test
    fun `read only guard allows non executing explain of select like statements`() {
        val allowedSql =
            listOf(
                "EXPLAIN SELECT * FROM orders",
                "EXPLAIN VERBOSE SELECT * FROM orders",
                "EXPLAIN (FORMAT JSON) SELECT * FROM orders",
                "EXPLAIN FORMAT=JSON SELECT * FROM orders",
                "EXPLAIN WITH latest AS (SELECT 1 AS id) SELECT * FROM latest",
            )

        allowedSql.forEach { sql ->
            assertThat(SqlSafety.isReadOnlySql(sql))
                .describedAs(sql)
                .isTrue()
        }
    }

    @Test
    fun `read only script guard checks every statement`() {
        assertThat(SqlSafety.isReadOnlyScript("SELECT 1; SHOW TABLES; VALUES (1)")).isTrue()
        assertThat(SqlSafety.isReadOnlyScript("SELECT 1; UPDATE orders SET status = 'done'")).isFalse()
        assertThat(
            SqlSafety.isReadOnlyScript(
                """
                SELECT 1;
                WITH deleted AS (DELETE FROM orders WHERE id = 1 RETURNING id)
                SELECT * FROM deleted
                """.trimIndent(),
            ),
        ).isFalse()
    }

    @Test
    fun `validation guard rejects executing explain statements`() {
        assertThat(SqlSafety.isSafeForValidation("EXPLAIN SELECT * FROM orders")).isTrue()
        assertThat(SqlSafety.isSafeForValidation("EXPLAIN ANALYZE SELECT * FROM orders")).isFalse()
        assertThat(SqlSafety.isSafeForValidation("EXPLAIN UPDATE orders SET status = 'done'")).isFalse()
        assertThat(SqlSafety.isSafeForValidation("SELECT 1; EXPLAIN DELETE FROM orders")).isFalse()
    }
}
