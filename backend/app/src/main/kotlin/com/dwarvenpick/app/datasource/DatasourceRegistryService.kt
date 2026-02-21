package com.dwarvenpick.app.datasource

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class ManagedDatasourceNotFoundException(
    override val message: String,
) : RuntimeException(message)

class CredentialProfileNotFoundException(
    override val message: String,
) : RuntimeException(message)

data class CatalogDatasourceEntry(
    val id: String,
    val name: String,
    val engine: DatasourceEngine,
    val credentialProfiles: Set<String>,
)

data class ConnectionSpec(
    val datasourceId: String,
    val datasourceName: String,
    val credentialProfile: String,
    val engine: DatasourceEngine,
    val driverId: String,
    val driverClass: String,
    val driverSource: String,
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val pool: PoolSettings,
)

private data class CredentialProfileRecord(
    val profileId: String,
    var username: String,
    var description: String?,
    var encryptedCredential: EncryptedCredential,
    var updatedAt: Instant,
)

private data class ManagedDatasourceRecord(
    val id: String,
    var name: String,
    var engine: DatasourceEngine,
    var host: String,
    var port: Int,
    var database: String?,
    var driverId: String,
    var driverClass: String,
    var pool: PoolSettings,
    var tls: TlsSettings,
    var options: MutableMap<String, String>,
    val credentialProfiles: MutableMap<String, CredentialProfileRecord>,
)

@Service
class DatasourceRegistryService(
    private val datasourceCredentialCryptoService: DatasourceCredentialCryptoService,
    private val driverRegistryService: DriverRegistryService,
    private val datasourceNetworkGuard: DatasourceNetworkGuard,
) {
    private val datasources = ConcurrentHashMap<String, ManagedDatasourceRecord>()
    private val seedPostgresHost =
        readStringEnv(
            key = "DWARVENPICK_SEED_POSTGRES_HOST",
            fallback = "localhost",
        )
    private val seedPostgresPort =
        readIntEnv(
            key = "DWARVENPICK_SEED_POSTGRES_PORT",
            fallback = 5432,
        )
    private val seedPostgresDatabase =
        readStringEnv(
            key = "DWARVENPICK_SEED_POSTGRES_DATABASE",
            fallback = "dwarvenpick",
        )
    private val seedPostgresUsername =
        readStringEnv(
            key = "DWARVENPICK_SEED_POSTGRES_USERNAME",
            fallback = "dwarvenpick",
        )
    private val seedPostgresPassword =
        readStringEnv(
            key = "DWARVENPICK_SEED_POSTGRES_PASSWORD",
            fallback = "dwarvenpick",
        )
    private val seedMysqlHost =
        readStringEnv(
            key = "DWARVENPICK_SEED_MYSQL_HOST",
            fallback = "localhost",
        )
    private val seedMysqlPort =
        readIntEnv(
            key = "DWARVENPICK_SEED_MYSQL_PORT",
            fallback = 3306,
        )
    private val seedMysqlDatabase =
        readStringEnv(
            key = "DWARVENPICK_SEED_MYSQL_DATABASE",
            fallback = "orders",
        )
    private val seedMysqlUsername =
        readStringEnv(
            key = "DWARVENPICK_SEED_MYSQL_USERNAME",
            fallback = "readonly",
        )
    private val seedMysqlPassword =
        readStringEnv(
            key = "DWARVENPICK_SEED_MYSQL_PASSWORD",
            fallback = "readonly",
        )

    @PostConstruct
    fun initialize() {
        resetState()
    }

    @Synchronized
    fun resetState() {
        datasources.clear()

        val postgresId =
            createDatasource(
                CreateDatasourceRequest(
                    name = "PostgreSQL Core",
                    engine = DatasourceEngine.POSTGRESQL,
                    host = seedPostgresHost,
                    port = seedPostgresPort,
                    database = seedPostgresDatabase,
                    driverId = "postgres-default",
                    tls = TlsSettings(mode = TlsMode.DISABLE),
                ),
            ).id
        upsertCredentialProfile(
            postgresId,
            "admin-ro",
            UpsertCredentialProfileRequest(
                username = seedPostgresUsername,
                password = seedPostgresPassword,
                description = "Admin readonly profile for local compose.",
            ),
        )
        upsertCredentialProfile(
            postgresId,
            "analyst-ro",
            UpsertCredentialProfileRequest(
                username = seedPostgresUsername,
                password = seedPostgresPassword,
                description = "Analyst readonly profile for local compose.",
            ),
        )

        val mysqlId =
            createDatasource(
                CreateDatasourceRequest(
                    name = "MySQL Orders",
                    engine = DatasourceEngine.MYSQL,
                    host = seedMysqlHost,
                    port = seedMysqlPort,
                    database = seedMysqlDatabase,
                    driverId = "mysql-default",
                    tls = TlsSettings(mode = TlsMode.DISABLE),
                    options =
                        mapOf(
                            "allowPublicKeyRetrieval" to "true",
                            "serverTimezone" to "UTC",
                        ),
                ),
            ).id
        upsertCredentialProfile(
            mysqlId,
            "admin-ro",
            UpsertCredentialProfileRequest(
                username = seedMysqlUsername,
                password = seedMysqlPassword,
                description = "Admin readonly profile.",
            ),
        )
        upsertCredentialProfile(
            mysqlId,
            "ops-ro",
            UpsertCredentialProfileRequest(
                username = seedMysqlUsername,
                password = seedMysqlPassword,
                description = "Ops readonly profile.",
            ),
        )

        val trinoId =
            createDatasource(
                CreateDatasourceRequest(
                    name = "Trino Warehouse",
                    engine = DatasourceEngine.TRINO,
                    host = "localhost",
                    port = 8088,
                    database = "hive.default",
                    driverId = "trino-default",
                    tls = TlsSettings(mode = TlsMode.REQUIRE, verifyServerCertificate = false),
                ),
            ).id
        upsertCredentialProfile(
            trinoId,
            "admin-ro",
            UpsertCredentialProfileRequest(
                username = "trino",
                password = "trino",
                description = "Admin readonly profile.",
            ),
        )
        upsertCredentialProfile(
            trinoId,
            "analyst-ro",
            UpsertCredentialProfileRequest(
                username = "trino",
                password = "trino",
                description = "Analyst readonly profile.",
            ),
        )
    }

    fun listManagedDatasources(): List<ManagedDatasourceResponse> =
        datasources.values
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
            .map { it.toResponse() }

    fun listCatalogEntries(): List<CatalogDatasourceEntry> =
        datasources.values
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
            .map { datasource ->
                CatalogDatasourceEntry(
                    id = datasource.id,
                    name = datasource.name,
                    engine = datasource.engine,
                    credentialProfiles = datasource.credentialProfiles.keys.toSet(),
                )
            }

    fun listDrivers(engine: DatasourceEngine?): List<DriverDescriptorResponse> =
        driverRegistryService.listDrivers(engine).map { descriptor ->
            DriverDescriptorResponse(
                driverId = descriptor.driverId,
                engine = descriptor.engine.name,
                driverClass = descriptor.driverClass,
                source = descriptor.source,
                available = descriptor.available,
                description = descriptor.description,
                message = descriptor.message,
            )
        }

    fun createDatasource(request: CreateDatasourceRequest): ManagedDatasourceResponse {
        val datasourceId = slugify(request.name)
        if (datasources.containsKey(datasourceId)) {
            throw IllegalArgumentException("Datasource '$datasourceId' already exists.")
        }

        val driver = driverRegistryService.resolveDriver(request.engine, request.driverId)
        val sanitizedHost = request.host.trim()
        if (sanitizedHost.isBlank()) {
            throw IllegalArgumentException("Datasource host is required.")
        }
        datasourceNetworkGuard.validateHost(sanitizedHost)

        datasources[datasourceId] =
            ManagedDatasourceRecord(
                id = datasourceId,
                name = request.name.trim(),
                engine = request.engine,
                host = sanitizedHost,
                port = request.port,
                database = request.database?.trim()?.ifBlank { null },
                driverId = driver.driverId,
                driverClass = driver.driverClass,
                pool = request.pool,
                tls = request.tls,
                options = request.options.toMutableMap(),
                credentialProfiles = linkedMapOf(),
            )

        return datasources.getValue(datasourceId).toResponse()
    }

    fun updateDatasource(
        datasourceId: String,
        request: UpdateDatasourceRequest,
    ): ManagedDatasourceResponse {
        val datasource =
            datasources[datasourceId]
                ?: throw ManagedDatasourceNotFoundException("Datasource '$datasourceId' was not found.")

        request.name?.let { datasource.name = it.trim() }
        request.host?.let {
            val sanitizedHost = it.trim()
            datasourceNetworkGuard.validateHost(sanitizedHost)
            datasource.host = sanitizedHost
        }
        request.port?.let { datasource.port = it }
        request.database?.let { datasource.database = it.trim().ifBlank { null } }
        request.pool?.let { datasource.pool = it }
        request.tls?.let { datasource.tls = it }
        request.options?.let {
            datasource.options.clear()
            datasource.options.putAll(it)
        }

        if (request.driverId != null) {
            val resolvedDriver = driverRegistryService.resolveDriver(datasource.engine, request.driverId)
            datasource.driverId = resolvedDriver.driverId
            datasource.driverClass = resolvedDriver.driverClass
        }

        return datasource.toResponse()
    }

    fun deleteDatasource(datasourceId: String): Boolean = datasources.remove(datasourceId) != null

    fun upsertCredentialProfile(
        datasourceId: String,
        profileId: String,
        request: UpsertCredentialProfileRequest,
    ): CredentialProfileResponse {
        val datasource =
            datasources[datasourceId]
                ?: throw ManagedDatasourceNotFoundException("Datasource '$datasourceId' was not found.")

        val normalizedProfileId = profileId.trim()
        if (normalizedProfileId.isBlank()) {
            throw IllegalArgumentException("Credential profile id is required.")
        }

        val encrypted = datasourceCredentialCryptoService.encryptPassword(request.password)
        val now = Instant.now()

        val record =
            datasource.credentialProfiles.computeIfAbsent(normalizedProfileId) {
                CredentialProfileRecord(
                    profileId = normalizedProfileId,
                    username = request.username.trim(),
                    description = request.description?.trim()?.ifBlank { null },
                    encryptedCredential = encrypted,
                    updatedAt = now,
                )
            }

        record.username = request.username.trim()
        record.description = request.description?.trim()?.ifBlank { null }
        record.encryptedCredential = encrypted
        record.updatedAt = now

        return record.toResponse()
    }

    fun listCredentialProfiles(datasourceId: String): List<CredentialProfileResponse> {
        val datasource =
            datasources[datasourceId]
                ?: throw ManagedDatasourceNotFoundException("Datasource '$datasourceId' was not found.")

        return datasource.credentialProfiles.values
            .sortedBy { credential -> credential.profileId }
            .map { credential -> credential.toResponse() }
    }

    fun resolveConnectionSpec(
        datasourceId: String,
        profileId: String,
        tlsOverride: TlsSettings?,
    ): ConnectionSpec {
        val datasource =
            datasources[datasourceId]
                ?: throw ManagedDatasourceNotFoundException("Datasource '$datasourceId' was not found.")

        val credential =
            datasource.credentialProfiles[profileId]
                ?: throw CredentialProfileNotFoundException(
                    "Credential profile '$profileId' was not found for datasource '$datasourceId'.",
                )

        val driver = driverRegistryService.resolveDriver(datasource.engine, datasource.driverId)
        driverRegistryService.ensureDriverReady(driver)
        datasourceNetworkGuard.validateHost(datasource.host)
        val tls = tlsOverride ?: datasource.tls
        val jdbcUrl = buildJdbcUrl(datasource, tls)
        val password = datasourceCredentialCryptoService.decryptPassword(credential.encryptedCredential)

        return ConnectionSpec(
            datasourceId = datasource.id,
            datasourceName = datasource.name,
            credentialProfile = profileId,
            engine = datasource.engine,
            driverId = driver.driverId,
            driverClass = driver.driverClass,
            driverSource = driver.source,
            jdbcUrl = jdbcUrl,
            username = credential.username,
            password = password,
            pool = datasource.pool,
        )
    }

    fun reencryptAllCredentialProfiles(): ReencryptCredentialsResponse {
        var updates = 0
        datasources.values.forEach { datasource ->
            datasource.credentialProfiles.values.forEach { credential ->
                credential.encryptedCredential =
                    datasourceCredentialCryptoService.reencrypt(credential.encryptedCredential)
                credential.updatedAt = Instant.now()
                updates += 1
            }
        }

        return ReencryptCredentialsResponse(
            updatedProfiles = updates,
            activeKeyId = datasourceCredentialCryptoService.activeKeyId(),
            message = "Re-encrypted $updates credential profile(s) with key ${datasourceCredentialCryptoService.activeKeyId()}.",
        )
    }

    fun encryptedPasswordForProfile(
        datasourceId: String,
        profileId: String,
    ): String {
        val datasource =
            datasources[datasourceId]
                ?: throw ManagedDatasourceNotFoundException("Datasource '$datasourceId' was not found.")
        val credential =
            datasource.credentialProfiles[profileId]
                ?: throw CredentialProfileNotFoundException(
                    "Credential profile '$profileId' was not found for datasource '$datasourceId'.",
                )
        return credential.encryptedCredential.ciphertext
    }

    fun hasDatasource(datasourceId: String): Boolean = datasources.containsKey(datasourceId)

    fun credentialProfilesForDatasource(datasourceId: String): Set<String> {
        val datasource =
            datasources[datasourceId]
                ?: throw ManagedDatasourceNotFoundException("Datasource '$datasourceId' was not found.")
        return datasource.credentialProfiles.keys.toSet()
    }

    private fun buildJdbcUrl(
        datasource: ManagedDatasourceRecord,
        tls: TlsSettings,
    ): String {
        val databaseSegment = datasource.database?.let { "/$it" } ?: ""
        val parameters = mutableMapOf<String, String>()
        parameters.putAll(datasource.options)

        when (datasource.engine) {
            DatasourceEngine.POSTGRESQL -> {
                parameters["sslmode"] = if (tls.mode == TlsMode.REQUIRE) "require" else "disable"
                if (tls.mode == TlsMode.REQUIRE && !tls.verifyServerCertificate) {
                    parameters["sslfactory"] = "org.postgresql.ssl.NonValidatingFactory"
                }
                return "jdbc:postgresql://${datasource.host}:${datasource.port}$databaseSegment${buildQuery(parameters)}"
            }

            DatasourceEngine.MYSQL -> {
                parameters["useSSL"] = (tls.mode == TlsMode.REQUIRE).toString()
                parameters["requireSSL"] = (tls.mode == TlsMode.REQUIRE).toString()
                parameters["verifyServerCertificate"] = tls.verifyServerCertificate.toString()
                return "jdbc:mysql://${datasource.host}:${datasource.port}$databaseSegment${buildQuery(parameters)}"
            }

            DatasourceEngine.MARIADB -> {
                parameters["useSsl"] = (tls.mode == TlsMode.REQUIRE).toString()
                parameters["trustServerCertificate"] = (!tls.verifyServerCertificate).toString()
                return "jdbc:mariadb://${datasource.host}:${datasource.port}$databaseSegment${buildQuery(parameters)}"
            }

            DatasourceEngine.TRINO -> {
                parameters["SSL"] = (tls.mode == TlsMode.REQUIRE).toString()
                if (tls.mode == TlsMode.REQUIRE && !tls.verifyServerCertificate) {
                    parameters["SSLVerification"] = "NONE"
                }
                return "jdbc:trino://${datasource.host}:${datasource.port}$databaseSegment${buildQuery(parameters)}"
            }

            DatasourceEngine.STARROCKS -> {
                parameters["useSSL"] = (tls.mode == TlsMode.REQUIRE).toString()
                parameters["requireSSL"] = (tls.mode == TlsMode.REQUIRE).toString()
                parameters["verifyServerCertificate"] = tls.verifyServerCertificate.toString()
                return "jdbc:mysql://${datasource.host}:${datasource.port}$databaseSegment${buildQuery(parameters)}"
            }

            DatasourceEngine.VERTICA -> {
                parameters["TLSmode"] = if (tls.mode == TlsMode.REQUIRE) "require" else "disable"
                if (tls.mode == TlsMode.REQUIRE) {
                    parameters["tls_verify_host"] = tls.verifyServerCertificate.toString()
                }
                return "jdbc:vertica://${datasource.host}:${datasource.port}$databaseSegment${buildQuery(parameters)}"
            }
        }
    }

    private fun buildQuery(parameters: Map<String, String>): String {
        if (parameters.isEmpty()) {
            return ""
        }

        val encoded =
            parameters.entries
                .sortedBy { entry -> entry.key }
                .joinToString("&") { entry -> "${entry.key}=${entry.value}" }
        return "?$encoded"
    }

    private fun slugify(input: String): String {
        val slug =
            input
                .trim()
                .lowercase(Locale.getDefault())
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')

        if (slug.isBlank()) {
            throw IllegalArgumentException("Datasource name must contain alphanumeric characters.")
        }

        return slug
    }

    private fun readStringEnv(
        key: String,
        fallback: String,
    ): String =
        System
            .getenv(key)
            ?.trim()
            ?.ifBlank { null }
            ?: fallback

    private fun readIntEnv(
        key: String,
        fallback: Int,
    ): Int =
        System.getenv(key)?.toIntOrNull()
            ?: fallback

    private fun ManagedDatasourceRecord.toResponse(): ManagedDatasourceResponse =
        ManagedDatasourceResponse(
            id = id,
            name = name,
            engine = engine.name,
            host = host,
            port = port,
            database = database,
            driverId = driverId,
            driverClass = driverClass,
            pool = pool,
            tls = tls,
            options = options.toMap(),
            credentialProfiles =
                credentialProfiles.values
                    .sortedBy { profile -> profile.profileId }
                    .map { profile -> profile.toResponse() },
        )

    private fun CredentialProfileRecord.toResponse(): CredentialProfileResponse =
        CredentialProfileResponse(
            profileId = profileId,
            username = username,
            description = description,
            encryptionKeyId = encryptedCredential.keyId,
            updatedAt = updatedAt.toString(),
        )
}
