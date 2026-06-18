package com.dwarvenpick.app.datasource

import com.dwarvenpick.app.persistence.PersistenceSchemaInitializer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.nio.file.Path
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Repository
class UploadedDriverRepository(
    jdbcTemplate: JdbcTemplate,
    @Suppress("unused") private val persistenceSchemaInitializer: PersistenceSchemaInitializer,
) {
    private val namedParameterJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
    private val rowMapper = RowMapper { resultSet, _ -> resultSet.toUploadedDriverRegistration() }

    fun list(): List<UploadedDriverRegistration> =
        namedParameterJdbcTemplate.query(
            """
            SELECT driver_id, engine, driver_class, description, jar_path
            FROM uploaded_jdbc_drivers
            ORDER BY engine, driver_id
            """.trimIndent(),
            emptyMap<String, Any>(),
            rowMapper,
        )

    fun save(registration: UploadedDriverRegistration) {
        namedParameterJdbcTemplate.update(
            "DELETE FROM uploaded_jdbc_drivers WHERE driver_id = :driverId",
            mapOf("driverId" to registration.driverId),
        )
        namedParameterJdbcTemplate.update(
            """
            INSERT INTO uploaded_jdbc_drivers (
              driver_id,
              engine,
              driver_class,
              description,
              jar_path,
              created_at
            ) VALUES (
              :driverId,
              :engine,
              :driverClass,
              :description,
              :jarPath,
              :createdAt
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("driverId", registration.driverId)
                .addValue("engine", registration.engine.name)
                .addValue("driverClass", registration.driverClass)
                .addValue("description", registration.description)
                .addValue("jarPath", registration.jarPath.toString())
                .addValue("createdAt", Timestamp.from(Instant.now())),
        )
    }

    fun clear() {
        namedParameterJdbcTemplate.update("DELETE FROM uploaded_jdbc_drivers", emptyMap<String, Any>())
    }

    private fun ResultSet.toUploadedDriverRegistration(): UploadedDriverRegistration =
        UploadedDriverRegistration(
            driverId = getString("driver_id"),
            engine = DatasourceEngine.valueOf(getString("engine")),
            driverClass = getString("driver_class"),
            description = getString("description"),
            jarPath = Path.of(getString("jar_path")),
        )
}
