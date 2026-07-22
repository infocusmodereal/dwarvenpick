package com.dwarvenpick.app.inspector.providers

import com.dwarvenpick.app.datasource.DatasourceEngine
import com.dwarvenpick.app.inspector.InspectedObjectType
import com.dwarvenpick.app.inspector.ObjectInspectorTable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.sql.PreparedStatement

class IndexInspectionTests {
    @Test
    fun `StarRocks tables use show index with safely quoted identifiers`() {
        val plan =
            buildIndexInspectionPlan(
                engine = DatasourceEngine.STARROCKS,
                objectType = InspectedObjectType.TABLE,
                schema = "schema`name",
                name = "table`name",
            )

        assertThat(plan.sql).isEqualTo("SHOW INDEX FROM `schema``name`.`table``name`")
        assertThat(plan.bindSchemaAndName).isFalse()
        assertThat(plan.normalizeStarRocksResult).isTrue()
    }

    @Test
    fun `StarRocks views retain information schema lookup`() {
        val plan =
            buildIndexInspectionPlan(
                engine = DatasourceEngine.STARROCKS,
                objectType = InspectedObjectType.VIEW,
                schema = "analytics",
                name = "recent_events",
            )

        assertThat(plan.sql).contains("FROM information_schema.statistics")
        assertThat(plan.bindSchemaAndName).isTrue()
        assertThat(plan.normalizeStarRocksResult).isFalse()
    }

    @Test
    fun `MySQL compatible plans bind schema and table parameters`() {
        listOf(DatasourceEngine.MYSQL, DatasourceEngine.MARIADB).forEach { engine ->
            val plan =
                buildIndexInspectionPlan(
                    engine = engine,
                    objectType = InspectedObjectType.TABLE,
                    schema = "analytics",
                    name = "events",
                )
            val statement = mock(PreparedStatement::class.java)

            bindIndexInspectionParameters(statement, plan, "analytics", "events")

            verify(statement).setString(1, "analytics")
            verify(statement).setString(2, "events")
            assertThat(plan.sql).contains("FROM information_schema.statistics")
        }
    }

    @Test
    fun `StarRocks show index plan does not bind identifier values`() {
        val plan =
            buildIndexInspectionPlan(
                engine = DatasourceEngine.STARROCKS,
                objectType = InspectedObjectType.TABLE,
                schema = "analytics",
                name = "events",
            )
        val statement = mock(PreparedStatement::class.java)

        bindIndexInspectionParameters(statement, plan, "analytics", "events")

        verify(statement, never()).setString(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `StarRocks index rows are normalized and sorted without losing limits`() {
        val normalized =
            normalizeStarRocksIndexTable(
                ObjectInspectorTable(
                    columns =
                        listOf(
                            "Table",
                            "Key_name",
                            "Seq_in_index",
                            "Column_name",
                            "Index_type",
                            "Null",
                            "Cardinality",
                            "Comment",
                            "Non_unique",
                        ),
                    rows =
                        listOf(
                            listOf("events", "z_idx", "2", "event_type", "BITMAP", "YES", "100", "z", "1"),
                            listOf("events", "a_idx", "1", "actor_id", "BITMAP", "NO", "50", "a", "0"),
                        ),
                    rowLimit = 100,
                    truncated = true,
                    cellLimit = 1_000,
                    cellsTruncated = true,
                ),
            )

        assertThat(normalized.columns).containsExactly(
            "name",
            "non_unique",
            "seq_in_index",
            "column_name",
            "index_type",
            "nullable",
            "cardinality",
            "comment",
        )
        assertThat(normalized.rows).containsExactly(
            listOf("a_idx", "0", "1", "actor_id", "BITMAP", "NO", "50", "a"),
            listOf("z_idx", "1", "2", "event_type", "BITMAP", "YES", "100", "z"),
        )
        assertThat(normalized.rowLimit).isEqualTo(100)
        assertThat(normalized.truncated).isTrue()
        assertThat(normalized.cellLimit).isEqualTo(1_000)
        assertThat(normalized.cellsTruncated).isTrue()
    }

    @Test
    fun `StarRocks index normalization tolerates empty and partial results`() {
        val empty = normalizeStarRocksIndexTable(ObjectInspectorTable(columns = emptyList(), rows = emptyList()))
        val partial =
            normalizeStarRocksIndexTable(
                ObjectInspectorTable(
                    columns = listOf("Key_name", "Column_name", "Index_type"),
                    rows = listOf(listOf("event_idx", "event_type", "BITMAP")),
                ),
            )

        assertThat(empty.rows).isEmpty()
        assertThat(partial.rows.single()).containsExactly(
            "event_idx",
            null,
            null,
            "event_type",
            "BITMAP",
            null,
            null,
            null,
        )
    }
}
