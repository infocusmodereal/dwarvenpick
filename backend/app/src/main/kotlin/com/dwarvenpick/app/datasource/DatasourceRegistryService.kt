package com.dwarvenpick.app.datasource

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Locale

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
    val sysadminCredentialProfiles: Set<String>,
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

data class CredentialProfileRecord(
    val profileId: String,
    var username: String,
    var description: String?,
    var sysadmin: Boolean,
    var encryptedCredential: EncryptedCredential,
    var updatedAt: Instant,
)

data class ManagedDatasourceRecord(
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
    var source: String = "ui",
)

@Service
class DatasourceRegistryService(
    private val datasourceCredentialCryptoService: DatasourceCredentialCryptoService,
    private val driverRegistryService: DriverRegistryService,
    private val datasourceNetworkGuard: DatasourceNetworkGuard,
    private val seedDatasourceProperties: SeedDatasourceProperties,
    private val tlsCertificateStore: TlsCertificateStore,
    private val datasourceRegistryRepository: DatasourceRegistryRepository,
) {
    @PostConstruct
    fun initialize() {
        ensureSeedDatasources()
    }

    @Synchronized
    fun resetState() {
        datasourceRegistryRepository.clear()
        ensureSeedDatasources()
    }

    @Synchronized
    fun ensureSeedDatasources() {
        if (!seedDatasourceProperties.enabled) {
            return
        }

        val postgres = seedDatasourceProperties.postgres
        val postgresId =
            upsertManagedDatasource(
                CreateDatasourceRequest(
                    name = "postgresql-core",
                    engine = DatasourceEngine.POSTGRESQL,
                    host = postgres.host,
                    port = postgres.port,
                    database = postgres.database,
                    driverId = "postgres-default",
                    tls = TlsSettings(mode = TlsMode.DISABLE),
                ),
                source = "seed",
            ).id
        upsertCredentialProfile(
            postgresId,
            "admin-ro",
            UpsertCredentialProfileRequest(
                username = postgres.username,
                password = postgres.password,
                description = "Admin readonly profile for local compose.",
                sysadmin = true,
            ),
        )
        upsertCredentialProfile(
            postgresId,
            "analyst-ro",
            UpsertCredentialProfileRequest(
                username = postgres.username,
                password = postgres.password,
                description = "Analyst readonly profile for local compose.",
            ),
        )

        val mysql = seedDatasourceProperties.mysql
        val mysqlId =
            upsertManagedDatasource(
                CreateDatasourceRequest(
                    name = "mysql-orders",
                    engine = DatasourceEngine.MYSQL,
                    host = mysql.host,
                    port = mysql.port,
                    database = mysql.database,
                    driverId = "mysql-default",
                    tls = TlsSettings(mode = TlsMode.DISABLE),
                    options =
                        mapOf(
                            "allowPublicKeyRetrieval" to "true",
                            "serverTimezone" to "UTC",
                        ),
                ),
                source = "seed",
            ).id
        upsertCredentialProfile(
            mysqlId,
            "admin-ro",
            UpsertCredentialProfileRequest(
                username = mysql.username,
                password = mysql.password,
                description = "Admin readonly profile.",
                sysadmin = true,
            ),
        )
        upsertCredentialProfile(
            mysqlId,
            "ops-ro",
            UpsertCredentialProfileRequest(
                username = mysql.username,
                password = mysql.password,
                description = "Ops readonly profile.",
            ),
        )

        val mariadb = seedDatasourceProperties.mariadb
        val mariadbId =
            upsertManagedDatasource(
                CreateDatasourceRequest(
                    name = "mariadb-mart",
                    engine = DatasourceEngine.MARIADB,
                    host = mariadb.host,
                    port = mariadb.port,
                    database = mariadb.database,
                    driverId = "mariadb-default",
                    tls = TlsSettings(mode = TlsMode.DISABLE),
                    options =
                        mapOf(
                            "sessionVariables" to "sql_mode=ANSI_QUOTES",
                        ),
                ),
                source = "seed",
            ).id
        upsertCredentialProfile(
            mariadbId,
            "admin-ro",
            UpsertCredentialProfileRequest(
                username = mariadb.username,
                password = mariadb.password,
                description = "Admin readonly profile.",
                sysadmin = true,
            ),
        )
        upsertCredentialProfile(
            mariadbId,
            "analyst-ro",
            UpsertCredentialProfileRequest(
                username = mariadb.username,
                password = mariadb.password,
                description = "Analyst readonly profile.",
            ),
        )

        val trino = seedDatasourceProperties.trino
        if (trino.enabled) {
            val trinoId =
                upsertManagedDatasource(
                    CreateDatasourceRequest(
                        name = "trino-warehouse",
                        engine = DatasourceEngine.TRINO,
                        host = trino.host,
                        port = trino.port,
                        database = trino.database,
                        driverId = "trino-default",
                        tls = TlsSettings(mode = TlsMode.DISABLE),
                    ),
                    source = "seed",
                ).id
            upsertCredentialProfile(
                trinoId,
                "admin-ro",
                UpsertCredentialProfileRequest(
                    username = trino.username,
                    password = trino.password,
                    description = "Admin readonly profile.",
                    sysadmin = true,
                ),
            )
            upsertCredentialProfile(
                trinoId,
                "analyst-ro",
                UpsertCredentialProfileRequest(
                    username = trino.username,
                    password = trino.password,
                    description = "Analyst readonly profile.",
                ),
            )
        }

        val starrocks = seedDatasourceProperties.starrocks
        val starrocksId =
            upsertManagedDatasource(
                CreateDatasourceRequest(
                    name = "starrocks-warehouse",
                    engine = DatasourceEngine.STARROCKS,
                    host = starrocks.host,
                    port = starrocks.port,
                    database = starrocks.database,
                    driverId = "starrocks-mysql",
                    tls = TlsSettings(mode = TlsMode.DISABLE),
                    options =
                        mapOf(
                            "allowPublicKeyRetrieval" to "true",
                            "serverTimezone" to "UTC",
                        ),
                ),
                source = "seed",
            ).id
        upsertCredentialProfile(
            starrocksId,
            "admin-ro",
            UpsertCredentialProfileRequest(
                username = starrocks.username,
                password = starrocks.password,
                description = "Admin readonly profile.",
                sysadmin = true,
            ),
        )
        upsertCredentialProfile(
            starrocksId,
            "analyst-ro",
            UpsertCredentialProfileRequest(
                username = starrocks.username,
                password = starrocks.password,
                description = "Analyst readonly profile.",
            ),
        )

        val verticaDriverAvailable =
            driverRegistryService
                .listDrivers(DatasourceEngine.VERTICA)
                .any { descriptor -> descriptor.available }
        if (verticaDriverAvailable) {
            val vertica = seedDatasourceProperties.vertica
            val verticaId =
                upsertManagedDatasource(
                    CreateDatasourceRequest(
                        name = "vertica-warehouse",
                        engine = DatasourceEngine.VERTICA,
                        host = vertica.host,
                        port = vertica.port,
                        database = vertica.database,
                        driverId = "vertica-external",
                        tls = TlsSettings(mode = TlsMode.DISABLE),
                    ),
                    source = "seed",
                ).id
            upsertCredentialProfile(
                verticaId,
                "admin-ro",
                UpsertCredentialProfileRequest(
                    username = vertica.username,
                    password = vertica.password,
                    description = "Admin readonly profile.",
                    sysadmin = true,
                ),
            )
            upsertCredentialProfile(
                verticaId,
                "analyst-ro",
                UpsertCredentialProfileRequest(
                    username = vertica.username,
                    password = vertica.password,
                    description = "Analyst readonly profile.",
                ),
            )
        }

        val aerospikeDriverAvailable =
            driverRegistryService
                .listDrivers(DatasourceEngine.AEROSPIKE)
                .any { descriptor -> descriptor.available }
        if (aerospikeDriverAvailable) {
            val aerospike = seedDatasourceProperties.aerospike
            val aerospikeId =
                upsertManagedDatasource(
                    CreateDatasourceRequest(
                        name = "aerospike-kv",
                        engine = DatasourceEngine.AEROSPIKE,
                        host = aerospike.host,
                        port = aerospike.port,
                        database = aerospike.database,
                        driverId = "aerospike-default",
                        tls = TlsSettings(mode = TlsMode.DISABLE),
                        options =
                            mapOf(
                                "sendKey" to "true",
                                "refuseScan" to "false",
                            ),
                    ),
                    source = "seed",
                ).id
            upsertCredentialProfile(
                aerospikeId,
                "admin-ro",
                UpsertCredentialProfileRequest(
                    username = aerospike.username,
                    password = aerospike.password,
                    description = "Admin profile for local compose.",
                    sysadmin = true,
                ),
            )
            upsertCredentialProfile(
                aerospikeId,
                "analyst-ro",
                UpsertCredentialProfileRequest(
                    username = aerospike.username,
                    password = aerospike.password,
                    description = "Analyst profile for local compose.",
                ),
            )
        }
    }

    fun listManagedDatasources(): List<ManagedDatasourceResponse> =
        datasourceRegistryRepository
            .list()
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
            .map { it.toResponse() }

    fun listCatalogEntries(): List<CatalogDatasourceEntry> =
        datasourceRegistryRepository
            .list()
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
            .map { datasource ->
                val sysadminProfiles =
                    datasource.credentialProfiles.values
                        .filter { profile -> profile.sysadmin }
                        .map { profile -> profile.profileId }
                        .toSet()
                CatalogDatasourceEntry(
                    id = datasource.id,
                    name = datasource.name,
                    engine = datasource.engine,
                    credentialProfiles = datasource.credentialProfiles.keys.toSet(),
                    sysadminCredentialProfiles = sysadminProfiles,
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
                version = descriptor.version,
            )
        }

    fun createDatasource(request: CreateDatasourceRequest): ManagedDatasourceResponse {
        val datasourceId = slugify(request.name)
        if (datasourceRegistryRepository.find(datasourceId) != null) {
            throw IllegalArgumentException("Datasource '$datasourceId' already exists.")
        }

        return persistDatasource(
            datasourceId = datasourceId,
            request = request,
            existing = null,
            source = "ui",
        )
    }

    fun upsertManagedDatasource(
        request: CreateDatasourceRequest,
        source: String = "config",
        allowUnresolvedHost: Boolean = false,
    ): ManagedDatasourceResponse {
        val datasourceId = slugify(request.name)
        return persistDatasource(
            datasourceId = datasourceId,
            request = request,
            existing = datasourceRegistryRepository.find(datasourceId),
            source = source,
            allowUnresolvedHost = allowUnresolvedHost,
        )
    }

    private fun persistDatasource(
        datasourceId: String,
        request: CreateDatasourceRequest,
        existing: ManagedDatasourceRecord?,
        source: String,
        allowUnresolvedHost: Boolean = false,
    ): ManagedDatasourceResponse {
        val driver = driverRegistryService.resolveDriver(request.engine, request.driverId)
        val sanitizedHost = request.host.trim()
        if (sanitizedHost.isBlank()) {
            throw IllegalArgumentException("Datasource host is required.")
        }
        datasourceNetworkGuard.validateHost(sanitizedHost, allowUnresolvedHost = allowUnresolvedHost)

        val datasource =
            existing
                ?: ManagedDatasourceRecord(
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
                    source = source,
                )

        datasource.name = request.name.trim()
        datasource.engine = request.engine
        datasource.host = sanitizedHost
        datasource.port = request.port
        datasource.database = request.database?.trim()?.ifBlank { null }
        datasource.driverId = driver.driverId
        datasource.driverClass = driver.driverClass
        datasource.pool = request.pool
        datasource.tls = request.tls
        datasource.options.clear()
        datasource.options.putAll(request.options)
        datasource.source = source

        datasourceRegistryRepository.saveDatasource(datasource)
        request.tlsCertificates?.let { tlsCertificateStore.apply(datasourceId, it) }

        return datasource.toResponse()
    }

    fun updateDatasource(
        datasourceId: String,
        request: UpdateDatasourceRequest,
    ): ManagedDatasourceResponse {
        val datasource =
            datasourceRegistryRepository.find(datasourceId)
                ?: throw ManagedDatasourceNotFoundException("Datasource '$datasourceId' was not found.")

        request.name?.let { datasource.name = it.trim() }
        request.host?.let {
            val sanitizedHost = it.trim()
            datasourceNetworkGuard.validateHost(sanitizedHost)
            datasource.host = sanitizedHost
        }
        request.port?.let { datasource.port = it }
        request.database?.let { database -> datasource.database = database.trim().ifBlank { null } }
        request.pool?.let { datasource.pool = it }
        request.tls?.let { datasource.tls = it }
        request.options?.let {
            datasource.options.clear()
            datasource.options.putAll(it)
        }
        request.tlsCertificates?.let { tlsCertificateStore.apply(datasourceId, it) }

        if (request.driverId != null) {
            val resolvedDriver = driverRegistryService.resolveDriver(datasource.engine, request.driverId)
            datasource.driverId = resolvedDriver.driverId
            datasource.driverClass = resolvedDriver.driverClass
        }

        datasourceRegistryRepository.saveDatasource(datasource)
        return datasource.toResponse()
    }

    fun deleteDatasource(datasourceId: String): Boolean {
        val removed = datasourceRegistryRepository.delete(datasourceId)
        if (removed) {
            tlsCertificateStore.clear(datasourceId)
        }
        return removed
    }

    fun listDatasourcesBySource(source: String): List<ManagedDatasourceRecord> = datasourceRegistryRepository.listBySource(source)

    fun deleteCredentialProfile(
        datasourceId: String,
        profileId: String,
    ): Boolean = datasourceRegistryRepository.deleteCredentialProfile(datasourceId, profileId)

    fun upsertCredentialProfile(
        datasourceId: String,
        profileId: String,
        request: UpsertCredentialProfileRequest,
    ): CredentialProfileResponse {
        val datasource =
            datasourceRegistryRepository.find(datasourceId)
                ?: throw ManagedDatasourceNotFoundException("Datasource '$datasourceId' was not found.")

        val normalizedProfileId = profileId.trim()
        if (normalizedProfileId.isBlank()) {
            throw IllegalArgumentException("Credential profile id is required.")
        }

        val encrypted =
            request.password?.let { password ->
                datasourceCredentialCryptoService.encryptPassword(password)
            }
        val now = Instant.now()

        val existingRecord = datasource.credentialProfiles[normalizedProfileId]
        val passwordOptionalForNewProfile =
            datasource.engine == DatasourceEngine.TRINO || datasource.engine == DatasourceEngine.AEROSPIKE
        if (existingRecord == null && encrypted == null && !passwordOptionalForNewProfile) {
            throw IllegalArgumentException("Credential password is required for a new profile.")
        }

        val record =
            existingRecord
                ?: CredentialProfileRecord(
                    profileId = normalizedProfileId,
                    username = request.username.trim(),
                    description = request.description?.trim()?.ifBlank { null },
                    sysadmin = request.sysadmin,
                    encryptedCredential = encrypted ?: datasourceCredentialCryptoService.encryptPassword(""),
                    updatedAt = now,
                )

        record.username = request.username.trim()
        record.description = request.description?.trim()?.ifBlank { null }
        record.sysadmin = request.sysadmin
        if (encrypted != null) {
            record.encryptedCredential = encrypted
        }
        record.updatedAt = now

        datasource.credentialProfiles[normalizedProfileId] = record
        datasourceRegistryRepository.saveCredentialProfile(datasourceId, record)

        return record.toResponse()
    }

    fun listCredentialProfiles(datasourceId: String): List<CredentialProfileResponse> {
        val datasource =
            datasourceRegistryRepository.find(datasourceId)
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
            datasourceRegistryRepository.find(datasourceId)
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
        datasourceRegistryRepository.list().forEach { datasource ->
            datasource.credentialProfiles.values.forEach { credential ->
                credential.encryptedCredential =
                    datasourceCredentialCryptoService.reencrypt(credential.encryptedCredential)
                credential.updatedAt = Instant.now()
                datasourceRegistryRepository.saveCredentialProfile(datasource.id, credential)
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
            datasourceRegistryRepository.find(datasourceId)
                ?: throw ManagedDatasourceNotFoundException("Datasource '$datasourceId' was not found.")
        val credential =
            datasource.credentialProfiles[profileId]
                ?: throw CredentialProfileNotFoundException(
                    "Credential profile '$profileId' was not found for datasource '$datasourceId'.",
                )
        return credential.encryptedCredential.ciphertext
    }

    fun hasDatasource(datasourceId: String): Boolean = datasourceRegistryRepository.find(datasourceId) != null

    fun engineForDatasource(datasourceId: String): DatasourceEngine =
        datasourceRegistryRepository.find(datasourceId)?.engine
            ?: throw ManagedDatasourceNotFoundException("Datasource '$datasourceId' was not found.")

    fun credentialProfilesForDatasource(datasourceId: String): Set<String> {
        val datasource =
            datasourceRegistryRepository.find(datasourceId)
                ?: throw ManagedDatasourceNotFoundException("Datasource '$datasourceId' was not found.")
        return datasource.credentialProfiles.keys.toSet()
    }

    private fun buildJdbcUrl(
        datasource: ManagedDatasourceRecord,
        tls: TlsSettings,
    ): String {
        val jdbcUrlOverride = datasource.options["jdbcUrl"]?.trim()
        if (!jdbcUrlOverride.isNullOrBlank()) {
            return jdbcUrlOverride
        }

        val databaseSegment = datasource.database?.let { "/$it" } ?: ""
        val parameters = mutableMapOf<String, String>()
        parameters.putAll(datasource.options)
        val tlsMaterials = tlsCertificateStore.resolvePaths(datasource.id)
        val effectiveVerifyServerCertificate = tls.verifyServerCertificate && !tls.allowSelfSigned

        when (datasource.engine) {
            DatasourceEngine.POSTGRESQL -> {
                parameters["sslmode"] =
                    if (tls.mode == TlsMode.REQUIRE) {
                        if (effectiveVerifyServerCertificate && tlsMaterials.caCertificatePem != null) {
                            "verify-ca"
                        } else {
                            "require"
                        }
                    } else {
                        "disable"
                    }
                if (tls.mode == TlsMode.REQUIRE && !effectiveVerifyServerCertificate) {
                    parameters["sslfactory"] = "org.postgresql.ssl.NonValidatingFactory"
                }
                tlsMaterials.caCertificatePem?.let { path -> parameters["sslrootcert"] = path.toString() }
                tlsMaterials.clientCertificatePem?.let { path -> parameters["sslcert"] = path.toString() }
                tlsMaterials.clientKeyPem?.let { path -> parameters["sslkey"] = path.toString() }
                return "jdbc:postgresql://${datasource.host}:${datasource.port}$databaseSegment${buildQuery(parameters)}"
            }

            DatasourceEngine.MYSQL -> {
                parameters["useSSL"] = (tls.mode == TlsMode.REQUIRE).toString()
                parameters["requireSSL"] = (tls.mode == TlsMode.REQUIRE).toString()
                parameters["verifyServerCertificate"] = effectiveVerifyServerCertificate.toString()
                parameters["sslMode"] =
                    if (tls.mode == TlsMode.REQUIRE) {
                        when {
                            !effectiveVerifyServerCertificate -> "REQUIRED"
                            tlsMaterials.trustStore != null -> "VERIFY_CA"
                            else -> "REQUIRED"
                        }
                    } else {
                        "DISABLED"
                    }
                if (tls.mode == TlsMode.REQUIRE && tlsMaterials.trustStore != null) {
                    parameters["trustCertificateKeyStoreUrl"] = tlsMaterials.trustStore.toUri().toString()
                    parameters["trustCertificateKeyStorePassword"] = tlsCertificateStore.storePassword()
                    parameters["trustCertificateKeyStoreType"] = "PKCS12"
                }
                if (tls.mode == TlsMode.REQUIRE && tlsMaterials.keyStore != null) {
                    parameters["clientCertificateKeyStoreUrl"] = tlsMaterials.keyStore.toUri().toString()
                    parameters["clientCertificateKeyStorePassword"] = tlsCertificateStore.storePassword()
                    parameters["clientCertificateKeyStoreType"] = "PKCS12"
                }
                return "jdbc:mysql://${datasource.host}:${datasource.port}$databaseSegment${buildQuery(parameters)}"
            }

            DatasourceEngine.MARIADB -> {
                parameters["useSsl"] = (tls.mode == TlsMode.REQUIRE).toString()
                parameters["trustServerCertificate"] = (!effectiveVerifyServerCertificate).toString()
                parameters["sslMode"] =
                    if (tls.mode == TlsMode.REQUIRE) {
                        when {
                            !effectiveVerifyServerCertificate -> "trust"
                            tlsMaterials.caCertificatePem != null -> "verify-ca"
                            else -> "trust"
                        }
                    } else {
                        "disable"
                    }
                if (tls.mode == TlsMode.REQUIRE && tlsMaterials.caCertificatePem != null) {
                    parameters["serverSslCert"] = tlsMaterials.caCertificatePem.toString()
                }
                if (tls.mode == TlsMode.REQUIRE && tlsMaterials.keyStore != null) {
                    parameters["keyStore"] = tlsMaterials.keyStore.toString()
                    parameters["keyStorePassword"] = tlsCertificateStore.storePassword()
                    parameters["keyPassword"] = tlsCertificateStore.storePassword()
                    parameters["keyStoreType"] = "PKCS12"
                }
                return "jdbc:mariadb://${datasource.host}:${datasource.port}$databaseSegment${buildQuery(parameters)}"
            }

            DatasourceEngine.TRINO -> {
                parameters["SSL"] = (tls.mode == TlsMode.REQUIRE).toString()
                if (tls.mode == TlsMode.REQUIRE) {
                    parameters["SSLVerification"] =
                        when {
                            !effectiveVerifyServerCertificate -> "NONE"
                            tlsMaterials.trustStore != null -> "CA"
                            else -> "FULL"
                        }
                    if (tlsMaterials.trustStore != null) {
                        parameters["SSLTrustStorePath"] = tlsMaterials.trustStore.toString()
                        parameters["SSLTrustStorePassword"] = tlsCertificateStore.storePassword()
                        parameters["SSLTrustStoreType"] = "PKCS12"
                    }
                    if (tlsMaterials.keyStore != null) {
                        parameters["SSLKeyStorePath"] = tlsMaterials.keyStore.toString()
                        parameters["SSLKeyStorePassword"] = tlsCertificateStore.storePassword()
                        parameters["SSLKeyStoreType"] = "PKCS12"
                    }
                }
                return "jdbc:trino://${datasource.host}:${datasource.port}$databaseSegment${buildQuery(parameters)}"
            }

            DatasourceEngine.STARROCKS -> {
                if (datasource.driverClass == "org.mariadb.jdbc.Driver") {
                    parameters["useSsl"] = (tls.mode == TlsMode.REQUIRE).toString()
                    parameters["trustServerCertificate"] = (!effectiveVerifyServerCertificate).toString()
                    parameters["sslMode"] =
                        if (tls.mode == TlsMode.REQUIRE) {
                            when {
                                !effectiveVerifyServerCertificate -> "trust"
                                tlsMaterials.caCertificatePem != null -> "verify-ca"
                                else -> "trust"
                            }
                        } else {
                            "disable"
                        }
                    if (tls.mode == TlsMode.REQUIRE && tlsMaterials.caCertificatePem != null) {
                        parameters["serverSslCert"] = tlsMaterials.caCertificatePem.toString()
                    }
                    if (tls.mode == TlsMode.REQUIRE && tlsMaterials.keyStore != null) {
                        parameters["keyStore"] = tlsMaterials.keyStore.toString()
                        parameters["keyStorePassword"] = tlsCertificateStore.storePassword()
                        parameters["keyPassword"] = tlsCertificateStore.storePassword()
                        parameters["keyStoreType"] = "PKCS12"
                    }
                } else {
                    parameters["useSSL"] = (tls.mode == TlsMode.REQUIRE).toString()
                    parameters["requireSSL"] = (tls.mode == TlsMode.REQUIRE).toString()
                    parameters["verifyServerCertificate"] = effectiveVerifyServerCertificate.toString()
                    parameters["sslMode"] =
                        if (tls.mode == TlsMode.REQUIRE) {
                            when {
                                !effectiveVerifyServerCertificate -> "REQUIRED"
                                tlsMaterials.trustStore != null -> "VERIFY_CA"
                                else -> "REQUIRED"
                            }
                        } else {
                            "DISABLED"
                        }
                    if (tls.mode == TlsMode.REQUIRE && tlsMaterials.trustStore != null) {
                        parameters["trustCertificateKeyStoreUrl"] = tlsMaterials.trustStore.toUri().toString()
                        parameters["trustCertificateKeyStorePassword"] = tlsCertificateStore.storePassword()
                        parameters["trustCertificateKeyStoreType"] = "PKCS12"
                    }
                    if (tls.mode == TlsMode.REQUIRE && tlsMaterials.keyStore != null) {
                        parameters["clientCertificateKeyStoreUrl"] = tlsMaterials.keyStore.toUri().toString()
                        parameters["clientCertificateKeyStorePassword"] = tlsCertificateStore.storePassword()
                        parameters["clientCertificateKeyStoreType"] = "PKCS12"
                    }
                }
                return "jdbc:mysql://${datasource.host}:${datasource.port}$databaseSegment${buildQuery(parameters)}"
            }

            DatasourceEngine.VERTICA -> {
                parameters["TLSmode"] = if (tls.mode == TlsMode.REQUIRE) "require" else "disable"
                if (tls.mode == TlsMode.REQUIRE) {
                    parameters["tls_verify_host"] = tls.verifyServerCertificate.toString()
                }
                return "jdbc:vertica://${datasource.host}:${datasource.port}$databaseSegment${buildQuery(parameters)}"
            }

            DatasourceEngine.AEROSPIKE -> {
                return "jdbc:aerospike:${datasource.host}:${datasource.port}$databaseSegment${buildQuery(parameters)}"
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
            tlsCertificates = tlsCertificateStore.status(id),
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
            sysadmin = sysadmin,
            encryptionKeyId = encryptedCredential.keyId,
            updatedAt = updatedAt.toString(),
        )
}
