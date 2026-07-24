package com.dwarvenpick.app.query

import com.dwarvenpick.app.datasource.DatasourceEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SqlSafetyTests {
    private val engines = DatasourceEngine.entries

    @Test
    fun `supported engines preserve common read only statements and ignore inert text`() {
        engines.forEach { engine ->
            val allowedSql =
                listOf(
                    "SELECT 1",
                    "SHOW TABLES",
                    "DESCRIBE orders",
                    "VALUES (1), (2)",
                    "(SELECT 1) UNION (SELECT 2)",
                    "SELECT 'delete; drop table orders' AS message",
                    "SELECT 1 /* update orders set status = 'done'; */",
                    "SELECT 1 -- delete from orders;\n",
                    "WITH latest AS (SELECT 'update' AS message) SELECT * FROM latest",
                    "WITH \"update\" AS (SELECT 1) SELECT * FROM \"update\"",
                )

            allowedSql.forEach { sql ->
                assertThat(SqlSafety.isReadOnlySql(sql, engine))
                    .describedAs("$engine: $sql")
                    .isTrue()
            }
        }
    }

    @Test
    fun `supported engines block writes admin commands and malformed input`() {
        val blockedSql =
            listOf(
                "INSERT INTO orders(id) VALUES (1)",
                "UPDATE orders SET status = 'done'",
                "DELETE FROM orders",
                "MERGE INTO orders USING updates ON orders.id = updates.id WHEN MATCHED THEN DELETE",
                "CREATE TABLE copied AS SELECT * FROM orders",
                "DROP TABLE orders",
                "TRUNCATE TABLE orders",
                "GRANT SELECT ON orders TO analyst",
                "CALL refresh_orders()",
                "SET ROLE admin",
                "PREPARE dangerous AS DELETE FROM orders",
                "EXECUTE dangerous",
                "(DELETE FROM orders)",
                "SELECT 'unterminated",
                "SELECT (1",
                "+ SELECT 1",
            )

        engines.forEach { engine ->
            blockedSql.forEach { sql ->
                assertThat(SqlSafety.isReadOnlySql(sql, engine))
                    .describedAs("$engine: $sql")
                    .isFalse()
            }
        }
    }

    @Test
    fun `data modifying CTEs and terminal writes fail closed`() {
        val blockedSql =
            listOf(
                "WITH changed AS (UPDATE orders SET status = 'done' RETURNING id) SELECT * FROM changed",
                "WITH deleted AS (DELETE FROM orders RETURNING id) SELECT * FROM deleted",
                "WITH inserted AS (INSERT INTO orders(id) VALUES (1) RETURNING id) SELECT * FROM inserted",
                "WITH latest AS (SELECT 1) UPDATE orders SET status = 'done'",
                "WITH latest AS (SELECT 1) SELECT * INTO copied_orders FROM latest",
            )

        engines.forEach { engine ->
            blockedSql.forEach { sql ->
                assertThat(SqlSafety.isReadOnlySql(sql, engine))
                    .describedAs("$engine: $sql")
                    .isFalse()
            }
        }
    }

    @Test
    fun `read only CTEs allow write-like identifiers and functions`() {
        val allowedSql =
            listOf(
                "WITH cleaned AS (SELECT REPLACE(url, 'a', 'b') AS url FROM events) SELECT * FROM cleaned",
                "WITH metadata AS (SELECT comment, `set`, load FROM events) SELECT * FROM metadata",
                "WITH first AS MATERIALIZED (SELECT 1 AS id), second AS (SELECT id FROM first) SELECT * FROM second",
                "WITH latest AS NOT\n MATERIALIZED (SELECT 1 AS id) SELECT * FROM latest",
            )

        engines.forEach { engine ->
            allowedSql.forEach { sql ->
                assertThat(SqlSafety.isReadOnlySql(sql, engine))
                    .describedAs("$engine: $sql")
                    .isTrue()
            }
        }
    }

    @Test
    fun `CTE names cannot hide terminal writes or later write bodies`() {
        val triggerNames = listOf("show", "describe", "desc", "values")

        engines.forEach { engine ->
            triggerNames.forEach { triggerName ->
                assertThat(
                    SqlSafety.isReadOnlySql(
                        "WITH t AS (SELECT 1), $triggerName AS (SELECT 1) DELETE FROM orders",
                        engine,
                    ),
                ).describedAs("$engine terminal write hidden after $triggerName CTE")
                    .isFalse()
                assertThat(
                    SqlSafety.isReadOnlySql(
                        "WITH t AS (SELECT 1), $triggerName AS (DELETE FROM orders RETURNING id) SELECT * FROM t",
                        engine,
                    ),
                ).describedAs("$engine write body hidden in $triggerName CTE")
                    .isFalse()
                assertThat(
                    SqlSafety.isReadOnlySql(
                        "WITH $triggerName AS (SELECT 1 AS id) SELECT * FROM $triggerName",
                        engine,
                    ),
                ).describedAs("$engine benign $triggerName CTE")
                    .isTrue()
            }
        }
    }

    @Test
    fun `select into write forms are blocked without matching inert text`() {
        val blockedSql =
            mapOf(
                DatasourceEngine.POSTGRESQL to "SELECT * INTO copied_orders FROM orders",
                DatasourceEngine.MYSQL to "SELECT * FROM orders INTO OUTFILE '/tmp/orders.csv'",
                DatasourceEngine.MARIADB to "SELECT * INTO DUMPFILE '/tmp/orders.bin' FROM orders",
                DatasourceEngine.STARROCKS to "SELECT * FROM orders INTO OUTFILE 's3://bucket/orders'",
            )

        blockedSql.forEach { (engine, sql) ->
            assertThat(SqlSafety.isReadOnlySql(sql, engine))
                .describedAs("$engine: $sql")
                .isFalse()
        }

        val inertInto =
            listOf(
                "SELECT 'into outfile' AS message",
                "SELECT id AS \"into\" FROM orders",
                "SELECT (SELECT count(*) FROM orders WHERE note = 'into') AS total",
            )
        engines.forEach { engine ->
            inertInto.forEach { sql ->
                assertThat(SqlSafety.isReadOnlySql(sql, engine))
                    .describedAs("$engine: $sql")
                    .isTrue()
            }
        }
        assertThat(SqlSafety.isReadOnlySql("SELECT `into` FROM orders", DatasourceEngine.MYSQL)).isTrue()
        assertThat(SqlSafety.isReadOnlySql("SELECT `into` FROM orders", DatasourceEngine.STARROCKS)).isTrue()
    }

    @Test
    fun `explain permits plans but blocks execution and wrapped writes`() {
        val allowedSql =
            listOf(
                "EXPLAIN SELECT * FROM orders",
                "EXPLAIN VERBOSE SELECT * FROM orders",
                "EXPLAIN (FORMAT JSON) SELECT * FROM orders",
                "EXPLAIN FORMAT=JSON SELECT * FROM orders",
                "EXPLAIN WITH latest AS (SELECT 1 AS id) SELECT * FROM latest",
            )
        val blockedSql =
            listOf(
                "EXPLAIN ANALYZE SELECT * FROM orders",
                "EXPLAIN (ANALYZE, FORMAT JSON) SELECT * FROM orders",
                "EXPLAIN UPDATE orders SET status = 'done'",
                "EXPLAIN DELETE FROM orders",
                "EXPLAIN SELECT * INTO copied_orders FROM orders",
            )

        engines.forEach { engine ->
            allowedSql.forEach { sql ->
                assertThat(SqlSafety.isReadOnlySql(sql, engine))
                    .describedAs("$engine: $sql")
                    .isTrue()
            }
            blockedSql.forEach { sql ->
                assertThat(SqlSafety.isReadOnlySql(sql, engine))
                    .describedAs("$engine: $sql")
                    .isFalse()
            }
        }
    }

    @Test
    fun `dialect quoting cannot hide statement boundaries from another engine`() {
        val postgresDollarScript = "SELECT \$tag\$safe; still safe\$tag\$ AS message; DELETE FROM orders"
        assertThat(SqlStatementSplitter.splitSqlStatements(postgresDollarScript, DatasourceEngine.POSTGRESQL))
            .hasSize(2)
        assertThat(SqlSafety.isReadOnlyScript(postgresDollarScript, DatasourceEngine.POSTGRESQL)).isFalse()
        assertThat(SqlStatementSplitter.splitSqlStatements(postgresDollarScript, DatasourceEngine.MYSQL))
            .hasSize(3)
        assertThat(SqlSafety.isReadOnlyScript(postgresDollarScript, DatasourceEngine.MYSQL)).isFalse()

        val mysqlHashScript = "SELECT 1 # comment; DELETE FROM hidden\n; DELETE FROM orders"
        assertThat(SqlStatementSplitter.splitSqlStatements(mysqlHashScript, DatasourceEngine.MYSQL))
            .hasSize(2)
        assertThat(SqlSafety.isReadOnlyScript(mysqlHashScript, DatasourceEngine.MYSQL)).isFalse()
        assertThat(SqlStatementSplitter.splitSqlStatements(mysqlHashScript, DatasourceEngine.POSTGRESQL))
            .hasSize(3)
        assertThat(SqlSafety.isReadOnlyScript(mysqlHashScript, DatasourceEngine.POSTGRESQL)).isFalse()

        val starRocksDashComment = "SELECT 1--comment; DELETE FROM hidden"
        assertThat(SqlStatementSplitter.splitSqlStatements(starRocksDashComment, DatasourceEngine.STARROCKS))
            .hasSize(1)
        assertThat(SqlSafety.isReadOnlyScript(starRocksDashComment, DatasourceEngine.STARROCKS)).isTrue()
        assertThat(SqlStatementSplitter.splitSqlStatements(starRocksDashComment, DatasourceEngine.MYSQL))
            .hasSize(2)
        assertThat(SqlSafety.isReadOnlyScript(starRocksDashComment, DatasourceEngine.MYSQL)).isFalse()

        val backtickScript = "SELECT `safe;identifier` FROM orders; DELETE FROM orders"
        assertThat(SqlStatementSplitter.splitSqlStatements(backtickScript, DatasourceEngine.MYSQL))
            .hasSize(2)
        assertThat(SqlStatementSplitter.splitSqlStatements(backtickScript, DatasourceEngine.POSTGRESQL))
            .hasSize(3)
        assertThat(SqlSafety.isReadOnlyScript(backtickScript, DatasourceEngine.POSTGRESQL)).isFalse()
    }

    @Test
    fun `engine specific literals and nested comments preserve semicolons`() {
        val postgres = "SELECT \$body\$safe; delete is text\$body\$ AS message"
        assertThat(SqlStatementSplitter.splitSqlStatements(postgres, DatasourceEngine.POSTGRESQL)).hasSize(1)
        assertThat(SqlSafety.isReadOnlySql(postgres, DatasourceEngine.POSTGRESQL)).isTrue()

        val vertica = "SELECT \$\$safe; delete is text\$\$ AS message"
        assertThat(SqlStatementSplitter.splitSqlStatements(vertica, DatasourceEngine.VERTICA)).hasSize(1)
        assertThat(SqlSafety.isReadOnlySql(vertica, DatasourceEngine.VERTICA)).isTrue()

        val nestedComment = "SELECT 1 /* outer /* delete; */ still comment */"
        assertThat(SqlStatementSplitter.splitSqlStatements(nestedComment, DatasourceEngine.POSTGRESQL)).hasSize(1)
        assertThat(SqlSafety.isReadOnlySql(nestedComment, DatasourceEngine.POSTGRESQL)).isTrue()
        assertThat(SqlSafety.isReadOnlySql(nestedComment, DatasourceEngine.VERTICA)).isTrue()

        val mysqlEscapedQuote = "SELECT 'safe\\'; delete is text' AS message"
        assertThat(SqlStatementSplitter.splitSqlStatements(mysqlEscapedQuote, DatasourceEngine.MYSQL)).hasSize(1)
        assertThat(SqlSafety.isReadOnlySql(mysqlEscapedQuote, DatasourceEngine.MYSQL)).isTrue()
    }

    @Test
    fun `mysql family executable comments always fail closed`() {
        val executableComments =
            listOf(
                "/*!50000 DELETE FROM orders */ SELECT 1",
                "/*M! DELETE FROM orders */ SELECT 1",
                "SELECT 1 /*! ; DROP TABLE orders */",
            )
        val mysqlFamily = listOf(DatasourceEngine.MYSQL, DatasourceEngine.MARIADB, DatasourceEngine.STARROCKS)

        mysqlFamily.forEach { engine ->
            executableComments.forEach { sql ->
                assertThat(SqlSafety.isReadOnlyScript(sql, engine))
                    .describedAs("$engine: $sql")
                    .isFalse()
            }
        }
    }

    @Test
    fun `validation guard rejects executing explain without rejecting write explain generation`() {
        engines.forEach { engine ->
            assertThat(SqlSafety.isSafeForValidation("EXPLAIN SELECT * FROM orders", engine)).isTrue()
            assertThat(SqlSafety.isSafeForValidation("EXPLAIN ANALYZE SELECT * FROM orders", engine)).isFalse()
            assertThat(SqlSafety.isSafeForValidation("EXPLAIN UPDATE orders SET status = 'done'", engine)).isFalse()
            assertThat(SqlSafety.isSafeForValidation("UPDATE orders SET status = 'done'", engine)).isTrue()
        }
    }
}
