package com.dwarvenpick.app.query

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource

class QueryStorageOutageTests {
    @Test fun `storage outage is surfaced rather than reported as missing or expired results`() {
        val source = Mockito.mock(DataSource::class.java)
        Mockito.`when`(source.connection).thenThrow(SQLException("Connection unavailable"))
        val repository = QueryResultPersistenceRepository(JdbcTemplate(source), ObjectMapper())
        assertThrows<CannotGetJdbcConnectionException> { repository.beginResultAccess("execution") }
        assertThrows<CannotGetJdbcConnectionException> { repository.expireResultsIfIdle("execution", Instant.now(), "Expired") }
    }
}
