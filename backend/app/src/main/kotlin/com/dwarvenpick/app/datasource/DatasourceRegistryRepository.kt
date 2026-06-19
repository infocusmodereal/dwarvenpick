package com.dwarvenpick.app.datasource

import com.dwarvenpick.app.persistence.PersistenceSchemaInitializer
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Repository
class DatasourceRegistryRepository(
    jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    @Suppress("unused") private val persistenceSchemaInitializer: PersistenceSchemaInitializer,
) {
    private val namedParameterJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
    private val datasourceRowMapper = RowMapper { resultSet, _ -> resultSet.toManagedDatasourceRecord() }
    private val poolType = object : TypeReference<PoolSettings>() {}
    private val tlsType = object : TypeReference<TlsSettings>() {}
    private val optionsType = object : TypeReference<Map<String, String>>() {}

    fun list(): List<ManagedDatasourceRecord> =
        namedParameterJdbcTemplate.query(
            """
            SELECT datasource_id, datasource_name, engine, host, port, datasource_database, driver_id,
                   driver_class, pool_json, tls_json, options_json, source, created_at, updated_at
            FROM managed_datasources
            ORDER BY datasource_name
            """.trimIndent(),
            emptyMap<String, Any>(),
            datasourceRowMapper,
        )

    fun find(datasourceId: String): ManagedDatasourceRecord? =
        namedParameterJdbcTemplate
            .query(
                """
                SELECT datasource_id, datasource_name, engine, host, port, datasource_database, driver_id,
                       driver_class, pool_json, tls_json, options_json, source, created_at, updated_at
                FROM managed_datasources
                WHERE datasource_id = :datasourceId
                """.trimIndent(),
                mapOf("datasourceId" to datasourceId),
                datasourceRowMapper,
            ).firstOrNull()

    fun listBySource(source: String): List<ManagedDatasourceRecord> =
        namedParameterJdbcTemplate.query(
            """
            SELECT datasource_id, datasource_name, engine, host, port, datasource_database, driver_id,
                   driver_class, pool_json, tls_json, options_json, source, created_at, updated_at
            FROM managed_datasources
            WHERE source = :source
            ORDER BY datasource_name
            """.trimIndent(),
            mapOf("source" to source),
            datasourceRowMapper,
        )

    @Transactional
    fun saveDatasource(record: ManagedDatasourceRecord) {
        val now = Instant.now()
        val exists = find(record.id) != null
        if (exists) {
            namedParameterJdbcTemplate.update(
                """
                UPDATE managed_datasources
                SET datasource_name = :datasourceName,
                    engine = :engine,
                    host = :host,
                    port = :port,
                    datasource_database = :datasourceDatabase,
                    driver_id = :driverId,
                    driver_class = :driverClass,
                    pool_json = :poolJson,
                    tls_json = :tlsJson,
                    options_json = :optionsJson,
                    source = :source,
                    updated_at = :updatedAt
                WHERE datasource_id = :datasourceId
                """.trimIndent(),
                record.toParameters(now),
            )
        } else {
            namedParameterJdbcTemplate.update(
                """
                INSERT INTO managed_datasources (
                  datasource_id,
                  datasource_name,
                  engine,
                  host,
                  port,
                  datasource_database,
                  driver_id,
                  driver_class,
                  pool_json,
                  tls_json,
                  options_json,
                  source,
                  created_at,
                  updated_at
                ) VALUES (
                  :datasourceId,
                  :datasourceName,
                  :engine,
                  :host,
                  :port,
                  :datasourceDatabase,
                  :driverId,
                  :driverClass,
                  :poolJson,
                  :tlsJson,
                  :optionsJson,
                  :source,
                  :createdAt,
                  :updatedAt
                )
                """.trimIndent(),
                record.toParameters(now, createdAt = now),
            )
        }
    }

    fun saveCredentialProfile(
        datasourceId: String,
        record: CredentialProfileRecord,
    ) {
        namedParameterJdbcTemplate.update(
            """
            DELETE FROM managed_credential_profiles
            WHERE datasource_id = :datasourceId
              AND profile_id = :profileId
            """.trimIndent(),
            mapOf("datasourceId" to datasourceId, "profileId" to record.profileId),
        )
        namedParameterJdbcTemplate.update(
            """
            INSERT INTO managed_credential_profiles (
              datasource_id,
              profile_id,
              username,
              description,
              sysadmin,
              encryption_key_id,
              encrypted_credential,
              updated_at
            ) VALUES (
              :datasourceId,
              :profileId,
              :username,
              :description,
              :sysadmin,
              :encryptionKeyId,
              :encryptedCredential,
              :updatedAt
            )
            """.trimIndent(),
            record.toParameters(datasourceId),
        )
    }

    fun deleteCredentialProfile(
        datasourceId: String,
        profileId: String,
    ): Boolean =
        namedParameterJdbcTemplate.update(
            """
            DELETE FROM managed_credential_profiles
            WHERE datasource_id = :datasourceId
              AND profile_id = :profileId
            """.trimIndent(),
            mapOf("datasourceId" to datasourceId, "profileId" to profileId),
        ) > 0

    @Transactional
    fun delete(datasourceId: String): Boolean {
        namedParameterJdbcTemplate.update(
            "DELETE FROM managed_credential_profiles WHERE datasource_id = :datasourceId",
            mapOf("datasourceId" to datasourceId),
        )
        return namedParameterJdbcTemplate.update(
            "DELETE FROM managed_datasources WHERE datasource_id = :datasourceId",
            mapOf("datasourceId" to datasourceId),
        ) > 0
    }

    @Transactional
    fun clear() {
        namedParameterJdbcTemplate.update("DELETE FROM managed_credential_profiles", emptyMap<String, Any>())
        namedParameterJdbcTemplate.update("DELETE FROM managed_datasources", emptyMap<String, Any>())
    }

    private fun ManagedDatasourceRecord.toParameters(
        now: Instant,
        createdAt: Instant? = null,
    ): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("datasourceId", id)
            .addValue("datasourceName", name)
            .addValue("engine", engine.name)
            .addValue("host", host)
            .addValue("port", port)
            .addValue("datasourceDatabase", database)
            .addValue("driverId", driverId)
            .addValue("driverClass", driverClass)
            .addValue("poolJson", objectMapper.writeValueAsString(pool))
            .addValue("tlsJson", objectMapper.writeValueAsString(tls))
            .addValue("optionsJson", objectMapper.writeValueAsString(options))
            .addValue("source", source)
            .addValue("createdAt", createdAt?.toTimestamp())
            .addValue("updatedAt", now.toTimestamp())

    private fun CredentialProfileRecord.toParameters(datasourceId: String): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("datasourceId", datasourceId)
            .addValue("profileId", profileId)
            .addValue("username", username)
            .addValue("description", description)
            .addValue("sysadmin", sysadmin)
            .addValue("encryptionKeyId", encryptedCredential.keyId)
            .addValue("encryptedCredential", encryptedCredential.ciphertext)
            .addValue("updatedAt", updatedAt.toTimestamp())

    private fun ResultSet.toManagedDatasourceRecord(): ManagedDatasourceRecord {
        val datasourceId = getString("datasource_id")
        return ManagedDatasourceRecord(
            id = datasourceId,
            name = getString("datasource_name"),
            engine = DatasourceEngine.valueOf(getString("engine")),
            host = getString("host"),
            port = getInt("port"),
            database = getString("datasource_database"),
            driverId = getString("driver_id"),
            driverClass = getString("driver_class"),
            pool = objectMapper.readValue(getString("pool_json"), poolType),
            tls = objectMapper.readValue(getString("tls_json"), tlsType),
            options = objectMapper.readValue(getString("options_json"), optionsType).toMutableMap(),
            credentialProfiles = credentialProfiles(datasourceId),
            source = getString("source"),
        )
    }

    private fun credentialProfiles(datasourceId: String): MutableMap<String, CredentialProfileRecord> =
        namedParameterJdbcTemplate
            .query(
                """
                SELECT profile_id, username, description, sysadmin, encryption_key_id, encrypted_credential, updated_at
                FROM managed_credential_profiles
                WHERE datasource_id = :datasourceId
                ORDER BY profile_id
                """.trimIndent(),
                mapOf("datasourceId" to datasourceId),
            ) { resultSet, _ ->
                CredentialProfileRecord(
                    profileId = resultSet.getString("profile_id"),
                    username = resultSet.getString("username"),
                    description = resultSet.getString("description"),
                    sysadmin = resultSet.getBoolean("sysadmin"),
                    encryptedCredential =
                        EncryptedCredential(
                            keyId = resultSet.getString("encryption_key_id"),
                            ciphertext = resultSet.getString("encrypted_credential"),
                        ),
                    updatedAt = resultSet.getTimestamp("updated_at").toInstant(),
                )
            }.associateBy { profile -> profile.profileId }
            .toMutableMap()

    private fun Instant.toTimestamp(): Timestamp = Timestamp.from(this)
}
