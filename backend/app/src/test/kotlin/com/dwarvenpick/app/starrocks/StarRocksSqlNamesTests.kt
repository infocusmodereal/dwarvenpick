package com.dwarvenpick.app.starrocks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StarRocksSqlNamesTests {
    @Test
    fun `renders internal database names without catalog prefix`() {
        val schema = StarRocksSchemaRef(catalog = STARROCKS_DEFAULT_CATALOG, database = "warehouse")

        assertThat(schema.displayName).isEqualTo("warehouse")
        assertThat(schema.qualifiedSchemaName()).isEqualTo("`warehouse`")
        assertThat(schema.qualifiedSchemaName(includeDefaultCatalog = true))
            .isEqualTo("`default_catalog`.`warehouse`")
        assertThat(schema.qualifiedObjectName("customers")).isEqualTo("`warehouse`.`customers`")
    }

    @Test
    fun `renders external catalog database names as catalog dot database`() {
        val schema = StarRocksSchemaRef(catalog = "message_archiver_lakehouse", database = "platform_events")

        assertThat(schema.displayName).isEqualTo("message_archiver_lakehouse.platform_events")
        assertThat(schema.qualifiedSchemaName())
            .isEqualTo("`message_archiver_lakehouse`.`platform_events`")
        assertThat(schema.qualifiedSchemaName(includeDefaultCatalog = true))
            .isEqualTo("`message_archiver_lakehouse`.`platform_events`")
        assertThat(schema.qualifiedObjectName("deals__events"))
            .isEqualTo("`message_archiver_lakehouse`.`platform_events`.`deals__events`")
    }

    @Test
    fun `quotes identifiers with embedded backticks`() {
        assertThat(quoteStarRocksIdentifier("catalog`name")).isEqualTo("`catalog``name`")
    }

    @Test
    fun `parses explorer schema labels`() {
        assertThat(parseStarRocksExplorerSchema("warehouse"))
            .isEqualTo(StarRocksSchemaRef(catalog = null, database = "warehouse"))
        assertThat(parseStarRocksExplorerSchema("message_archiver_lakehouse.platform_events"))
            .isEqualTo(
                StarRocksSchemaRef(
                    catalog = "message_archiver_lakehouse",
                    database = "platform_events",
                ),
            )
    }
}
