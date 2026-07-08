package com.dwarvenpick.app.query

import com.dwarvenpick.app.datasource.DatasourceEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.sql.Connection
import java.sql.Statement

class QueryDefaultSchemaTests {
    @Test
    fun `starrocks default schema uses explicit default catalog and evicts pooled connection`() {
        val connection = mock(Connection::class.java)
        val statement = mock(Statement::class.java)
        `when`(connection.createStatement()).thenReturn(statement)

        val applied =
            QueryDefaultSchema.apply(
                connection = connection,
                engine = DatasourceEngine.STARROCKS,
                defaultSchema = "Viper2",
            )

        verify(statement).execute("USE `default_catalog`.`Viper2`")
        verify(statement).close()
        assertThat(applied.evictConnectionOnClose).isTrue()
    }

    @Test
    fun `starrocks explicit default catalog is preserved for USE`() {
        val connection = mock(Connection::class.java)
        val statement = mock(Statement::class.java)
        `when`(connection.createStatement()).thenReturn(statement)

        QueryDefaultSchema.apply(
            connection = connection,
            engine = DatasourceEngine.STARROCKS,
            defaultSchema = "default_catalog.Viper2",
        )

        verify(statement).execute("USE `default_catalog`.`Viper2`")
    }

    @Test
    fun `starrocks external catalog schema preserves catalog and database parts`() {
        val connection = mock(Connection::class.java)
        val statement = mock(Statement::class.java)
        `when`(connection.createStatement()).thenReturn(statement)

        QueryDefaultSchema.apply(
            connection = connection,
            engine = DatasourceEngine.STARROCKS,
            defaultSchema = "message_archiver_lakehouse.platform_events",
        )

        verify(statement).execute("USE `message_archiver_lakehouse`.`platform_events`")
    }

    @Test
    fun `mysql default schema is quoted as one database identifier`() {
        val connection = mock(Connection::class.java)
        val statement = mock(Statement::class.java)
        `when`(connection.createStatement()).thenReturn(statement)

        val applied =
            QueryDefaultSchema.apply(
                connection = connection,
                engine = DatasourceEngine.MYSQL,
                defaultSchema = "analytics`prod",
            )

        verify(statement).execute("USE `analytics``prod`")
        assertThat(applied.evictConnectionOnClose).isTrue()
    }

    @Test
    fun `jdbc schema is restored without evicting connection`() {
        val connection = mock(Connection::class.java)
        `when`(connection.schema).thenReturn("public")

        val applied =
            QueryDefaultSchema.apply(
                connection = connection,
                engine = DatasourceEngine.POSTGRESQL,
                defaultSchema = "warehouse",
            )
        applied.close()

        val ordered = inOrder(connection)
        ordered.verify(connection).schema = "warehouse"
        ordered.verify(connection).schema = "public"
        assertThat(applied.evictConnectionOnClose).isFalse()
    }
}
