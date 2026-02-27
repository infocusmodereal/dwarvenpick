package com.dwarvenpick.app.datasource

import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.SQLFeatureNotSupportedException
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.math.max

private data class BuiltInDriverSpec(
    val driverId: String,
    val engine: DatasourceEngine,
    val driverClass: String,
    val description: String,
    val unavailableMessage: String,
)

private data class UploadedDriverRegistration(
    val driverId: String,
    val engine: DatasourceEngine,
    val driverClass: String,
    val description: String,
    val jarPath: Path,
)

private data class MavenVersionCacheKey(
    val preset: MavenDriverPreset,
    val includeSnapshots: Boolean,
    val limit: Int,
)

private data class MavenVersionCacheEntry(
    val versions: List<String>,
    val fetchedAt: Instant,
)

data class DriverDescriptor(
    val driverId: String,
    val engine: DatasourceEngine,
    val driverClass: String,
    val source: String,
    val description: String,
    val available: Boolean,
    val message: String,
    val version: String?,
)

class DriverNotAvailableException(
    override val message: String,
) : RuntimeException(message)

@Service
class DriverRegistryService(
    private val driverRegistryProperties: DriverRegistryProperties,
) {
    private val verticaDriverClass = "com.vertica.jdbc.Driver"
    private val verticaDriverId = "vertica-external"
    private val registeredExternalDrivers = ConcurrentHashMap.newKeySet<String>()
    private val externalDriverClassLoaders = ConcurrentHashMap<String, URLClassLoader>()
    private val uploadedDrivers = ConcurrentHashMap<String, UploadedDriverRegistration>()
    private val mavenVersionCache = ConcurrentHashMap<MavenVersionCacheKey, MavenVersionCacheEntry>()
    private val mavenVersionCacheTtl = Duration.ofMinutes(10)
    private val httpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(6))
            .build()
    private val mavenVersionPattern = Regex("<version>([^<]+)</version>", RegexOption.IGNORE_CASE)

    private val builtInDriverSpecs =
        listOf(
            BuiltInDriverSpec(
                driverId = "postgres-default",
                engine = DatasourceEngine.POSTGRESQL,
                driverClass = "org.postgresql.Driver",
                description = "PostgreSQL JDBC driver",
                unavailableMessage =
                    "PostgreSQL driver class is unavailable. Upload a compatible driver jar.",
            ),
            BuiltInDriverSpec(
                driverId = "mysql-default",
                engine = DatasourceEngine.MYSQL,
                driverClass = "com.mysql.cj.jdbc.Driver",
                description = "MySQL Connector/J",
                unavailableMessage =
                    "MySQL Connector/J class is unavailable. Upload mysql-connector-j jar.",
            ),
            BuiltInDriverSpec(
                driverId = "mariadb-default",
                engine = DatasourceEngine.MARIADB,
                driverClass = "org.mariadb.jdbc.Driver",
                description = "MariaDB JDBC driver",
                unavailableMessage =
                    "MariaDB JDBC class is unavailable. Upload mariadb-java-client jar.",
            ),
            BuiltInDriverSpec(
                driverId = "trino-default",
                engine = DatasourceEngine.TRINO,
                driverClass = "io.trino.jdbc.TrinoDriver",
                description = "Trino JDBC driver",
                unavailableMessage =
                    "Trino JDBC class is unavailable. Upload the trino-jdbc jar.",
            ),
            BuiltInDriverSpec(
                driverId = "starrocks-mysql",
                engine = DatasourceEngine.STARROCKS,
                driverClass = "com.mysql.cj.jdbc.Driver",
                description = "StarRocks via MySQL protocol (MySQL Connector/J)",
                unavailableMessage =
                    "MySQL Connector/J is unavailable. Upload mysql-connector-j jar for StarRocks.",
            ),
            BuiltInDriverSpec(
                driverId = "starrocks-mariadb",
                engine = DatasourceEngine.STARROCKS,
                driverClass = "org.mariadb.jdbc.Driver",
                description = "StarRocks via MySQL protocol (MariaDB JDBC driver)",
                unavailableMessage =
                    "MariaDB JDBC class is unavailable. Upload mariadb-java-client jar for StarRocks.",
            ),
        )

    @Synchronized
    fun ensureDriverReady(driverDescriptor: DriverDescriptor) {
        if (!driverDescriptor.available) {
            throw DriverNotAvailableException(
                buildString {
                    append("Driver '${driverDescriptor.driverId}' is unavailable. ")
                    append(driverDescriptor.message)
                },
            )
        }

        if (classExistsOnClasspath(driverDescriptor.driverClass)) {
            return
        }

        registerExternalDriver(driverDescriptor)
    }

    fun listDrivers(engine: DatasourceEngine? = null): List<DriverDescriptor> {
        val allDescriptors = builtInDriverDescriptors() + uploadedDriverDescriptors() + verticaExternalDescriptors()
        return allDescriptors
            .asSequence()
            .filter { descriptor -> engine == null || descriptor.engine == engine }
            .sortedWith(compareBy({ it.engine.name }, { it.driverId }))
            .toList()
    }

    fun resolveDriver(
        engine: DatasourceEngine,
        requestedDriverId: String?,
    ): DriverDescriptor {
        val candidates = listDrivers(engine)
        if (candidates.isEmpty()) {
            throw DriverNotAvailableException("No registered drivers were found for engine '$engine'.")
        }

        val selected =
            if (requestedDriverId.isNullOrBlank()) {
                candidates.firstOrNull { it.available } ?: candidates.first()
            } else {
                candidates.firstOrNull { it.driverId == requestedDriverId.trim() }
                    ?: throw DriverNotAvailableException(
                        "Driver '$requestedDriverId' is not registered for engine '$engine'.",
                    )
            }

        if (!selected.available) {
            throw DriverNotAvailableException(
                buildString {
                    append("Driver '${selected.driverId}' is unavailable. ")
                    append(selected.message)
                },
            )
        }

        return selected
    }

    @Synchronized
    fun uploadDriver(
        engine: DatasourceEngine,
        driverClass: String,
        jarFileName: String,
        jarBytes: ByteArray,
        requestedDriverId: String?,
        requestedDescription: String?,
    ): DriverDescriptor {
        if (!jarFileName.endsWith(".jar", ignoreCase = true)) {
            throw IllegalArgumentException("Only .jar files are accepted for JDBC driver uploads.")
        }
        if (jarBytes.isEmpty()) {
            throw IllegalArgumentException("Uploaded driver jar is empty.")
        }
        val normalizedDriverClass = driverClass.trim()
        if (normalizedDriverClass.isBlank()) {
            throw IllegalArgumentException("Driver class is required.")
        }

        val driverId =
            normalizeDriverId(
                requestedDriverId
                    ?.takeIf { value -> value.isNotBlank() }
                    ?: "${engine.name.lowercase()}-${normalizedDriverClass.substringAfterLast('.').lowercase()}",
            )

        if (builtInDriverSpecs.any { spec -> spec.driverId == driverId } || driverId == verticaDriverId) {
            throw IllegalArgumentException(
                "Driver id '$driverId' is reserved by a built-in driver. Choose a different id.",
            )
        }

        val externalDirPath = Path.of(externalDriverDirectory())
        val targetDir = externalDirPath.resolve("uploads").resolve(driverId)
        Files.createDirectories(targetDir)

        val sanitizedFileName = sanitizeFileName(jarFileName)
        val targetJarPath = targetDir.resolve(sanitizedFileName)
        Files.write(targetJarPath, jarBytes)

        uploadedDrivers[driverId] =
            UploadedDriverRegistration(
                driverId = driverId,
                engine = engine,
                driverClass = normalizedDriverClass,
                description = requestedDescription?.trim()?.ifBlank { null } ?: "Uploaded JDBC driver",
                jarPath = targetJarPath,
            )

        registeredExternalDrivers.remove(driverId)
        externalDriverClassLoaders.remove(driverId)?.close()

        val descriptor = uploadedDriverDescriptor(uploadedDrivers.getValue(driverId))
        if (!descriptor.available) {
            throw DriverNotAvailableException(
                "Uploaded jar does not provide class '$normalizedDriverClass'. Verify the class name and jar.",
            )
        }

        return descriptor
    }

    fun externalDriverDirectory(): String = driverRegistryProperties.externalDir

    fun installMavenDriver(
        preset: MavenDriverPreset,
        version: String,
        requestedDriverId: String?,
        requestedDescription: String?,
    ): DriverDescriptor {
        if (!driverRegistryProperties.maven.enabled) {
            throw IllegalStateException(
                "Maven driver downloads are disabled. Set DWARVENPICK_DRIVERS_MAVEN_ENABLED=true to enable.",
            )
        }

        val normalizedVersion = version.trim()
        if (normalizedVersion.isBlank()) {
            throw IllegalArgumentException("Version is required.")
        }

        if (!Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,120}$").matches(normalizedVersion)) {
            throw IllegalArgumentException("Version contains invalid characters.")
        }

        val repositoryUrl =
            driverRegistryProperties.maven.repositoryUrl
                .trim()
                .ifBlank { "https://repo1.maven.org/maven2/" }
                .let { url -> if (url.endsWith("/")) url else "$url/" }

        val groupPath = preset.groupId.replace('.', '/')
        val jarFileName = "${preset.artifactId}-$normalizedVersion.jar"
        val jarUri = URI.create("$repositoryUrl$groupPath/${preset.artifactId}/$normalizedVersion/$jarFileName")

        val jarBytes =
            runCatching {
                val request =
                    HttpRequest
                        .newBuilder(jarUri)
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "dwarvenpick")
                        .GET()
                        .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
                if (response.statusCode() != 200) {
                    throw DriverNotAvailableException(
                        "Failed to download driver jar (HTTP ${response.statusCode()}). Verify the version exists.",
                    )
                }
                response.body()
            }.getOrElse { ex ->
                throw DriverNotAvailableException(ex.message ?: "Failed to download driver jar.")
            }

        val maxJarSizeMb = max(1, driverRegistryProperties.maven.maxJarSizeMb)
        val maxJarBytes = maxJarSizeMb.toLong() * 1024L * 1024L
        if (jarBytes.size.toLong() > maxJarBytes) {
            throw IllegalArgumentException("Downloaded jar exceeds maximum size (${maxJarSizeMb}MiB).")
        }

        val defaultDriverId = "${preset.name.lowercase().replace('_', '-')}-$normalizedVersion"
        val driverId = requestedDriverId?.trim()?.ifBlank { null } ?: defaultDriverId
        val description =
            requestedDescription?.trim()?.ifBlank { null }
                ?: "${preset.defaultDescription} v$normalizedVersion"

        return uploadDriver(
            engine = preset.engine,
            driverClass = preset.driverClass,
            jarFileName = jarFileName,
            jarBytes = jarBytes,
            requestedDriverId = driverId,
            requestedDescription = description,
        )
    }

    fun listMavenDriverVersions(
        preset: MavenDriverPreset,
        limit: Int,
        includeSnapshots: Boolean,
    ): List<String> {
        if (!driverRegistryProperties.maven.enabled) {
            throw IllegalStateException(
                "Maven driver downloads are disabled. Set DWARVENPICK_DRIVERS_MAVEN_ENABLED=true to enable.",
            )
        }

        val normalizedLimit = limit.coerceIn(1, 200)
        val cacheKey =
            MavenVersionCacheKey(
                preset = preset,
                includeSnapshots = includeSnapshots,
                limit = normalizedLimit,
            )
        val now = Instant.now()

        val cached = mavenVersionCache[cacheKey]
        if (cached != null && Duration.between(cached.fetchedAt, now) < mavenVersionCacheTtl) {
            return cached.versions
        }

        val repositoryUrl =
            driverRegistryProperties.maven.repositoryUrl
                .trim()
                .ifBlank { "https://repo1.maven.org/maven2/" }
                .let { url -> if (url.endsWith("/")) url else "$url/" }

        val groupPath = preset.groupId.replace('.', '/')
        val metadataUri =
            URI.create(
                "$repositoryUrl$groupPath/${preset.artifactId}/maven-metadata.xml",
            )

        val metadataXml =
            runCatching {
                val request =
                    HttpRequest
                        .newBuilder(metadataUri)
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "dwarvenpick")
                        .GET()
                        .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) {
                    throw DriverNotAvailableException(
                        "Failed to fetch available versions (HTTP ${response.statusCode()}).",
                    )
                }
                response.body()
            }.getOrElse { ex ->
                throw DriverNotAvailableException(ex.message ?: "Failed to fetch available versions.")
            }

        val versions =
            mavenVersionPattern
                .findAll(metadataXml)
                .map { match -> match.groupValues[1].trim() }
                .filter { value -> value.isNotBlank() }
                .filter { value -> includeSnapshots || !value.contains("SNAPSHOT", ignoreCase = true) }
                .toList()
                .distinct()
                .asReversed()
                .take(normalizedLimit)

        mavenVersionCache[cacheKey] = MavenVersionCacheEntry(versions = versions, fetchedAt = now)
        return versions
    }

    private fun builtInDriverDescriptors(): List<DriverDescriptor> {
        val externalJars = externalDriverJarFiles(Path.of(externalDriverDirectory()))

        return builtInDriverSpecs.map { spec ->
            val classpathAvailable = classExistsOnClasspath(spec.driverClass)
            val externalAvailable =
                !classpathAvailable &&
                    externalJars.isNotEmpty() &&
                    classExistsInExternalJars(spec.driverClass, externalJars)
            val version =
                if (classpathAvailable) {
                    resolveDriverVersion(spec.driverClass)
                } else if (externalAvailable) {
                    resolveDriverVersion(spec.driverClass, externalJars)
                } else {
                    null
                }

            DriverDescriptor(
                driverId = spec.driverId,
                engine = spec.engine,
                driverClass = spec.driverClass,
                source = "built-in",
                description = spec.description,
                available = classpathAvailable || externalAvailable,
                message =
                    when {
                        classpathAvailable -> "Driver resolved from the application classpath."
                        externalAvailable -> "Driver resolved from uploaded/external jars."
                        else -> spec.unavailableMessage
                    },
                version = version,
            )
        }
    }

    private fun uploadedDriverDescriptors(): List<DriverDescriptor> =
        uploadedDrivers.values.map { registration -> uploadedDriverDescriptor(registration) }

    private fun uploadedDriverDescriptor(registration: UploadedDriverRegistration): DriverDescriptor {
        val jarFiles = listOf(registration.jarPath)
        val available = classExistsInExternalJars(registration.driverClass, jarFiles)
        return DriverDescriptor(
            driverId = registration.driverId,
            engine = registration.engine,
            driverClass = registration.driverClass,
            source = "uploaded",
            description = registration.description,
            available = available,
            message =
                if (available) {
                    "Driver class resolved from ${registration.jarPath.fileName}."
                } else {
                    "Driver class '${registration.driverClass}' not found in ${registration.jarPath.fileName}."
                },
            version = resolveDriverVersion(registration.driverClass, jarFiles),
        )
    }

    private fun verticaExternalDescriptors(): List<DriverDescriptor> {
        val directoryPath = Path.of(externalDriverDirectory())
        val jarFiles = externalDriverJarFiles(directoryPath)

        if (!directoryPath.exists() || !directoryPath.isDirectory()) {
            return listOf(
                DriverDescriptor(
                    driverId = verticaDriverId,
                    engine = DatasourceEngine.VERTICA,
                    driverClass = verticaDriverClass,
                    source = "external",
                    description = "Vertica JDBC driver loaded from mounted jars.",
                    available = false,
                    message =
                        "External driver directory '$directoryPath' is missing. Mount Vertica driver jars there.",
                    version = null,
                ),
            )
        }

        if (jarFiles.isEmpty()) {
            return listOf(
                DriverDescriptor(
                    driverId = verticaDriverId,
                    engine = DatasourceEngine.VERTICA,
                    driverClass = verticaDriverClass,
                    source = "external",
                    description = "Vertica JDBC driver loaded from mounted jars.",
                    available = false,
                    message = "No driver jars found in '$directoryPath'.",
                    version = null,
                ),
            )
        }

        val available = classExistsInExternalJars(verticaDriverClass, jarFiles)
        return listOf(
            DriverDescriptor(
                driverId = verticaDriverId,
                engine = DatasourceEngine.VERTICA,
                driverClass = verticaDriverClass,
                source = "external",
                description = "Vertica JDBC driver loaded from mounted jars.",
                available = available,
                message =
                    if (available) {
                        "Vertica driver class resolved from external jars."
                    } else {
                        "Vertica driver class not found in configured external jars."
                    },
                version = resolveDriverVersion(verticaDriverClass, jarFiles),
            ),
        )
    }

    private fun classExistsOnClasspath(driverClass: String): Boolean =
        runCatching {
            Class.forName(driverClass)
            true
        }.getOrElse { false }

    private fun classExistsInExternalJars(
        driverClass: String,
        jars: List<Path>,
    ): Boolean {
        if (jars.isEmpty()) {
            return false
        }

        val urls = jars.map { jar -> jar.toUri().toURL() }.toTypedArray()
        return URLClassLoader(urls, javaClass.classLoader).use { classLoader ->
            runCatching {
                Class.forName(driverClass, false, classLoader)
                true
            }.getOrElse { false }
        }
    }

    private fun resolveDriverVersion(
        driverClass: String,
        jars: List<Path>? = null,
    ): String? {
        if (jars != null) {
            if (jars.isEmpty()) {
                return null
            }
            val urls = jars.map { jar -> jar.toUri().toURL() }.toTypedArray()
            return URLClassLoader(urls, javaClass.classLoader).use { classLoader ->
                resolveDriverVersionWithClassLoader(driverClass, classLoader)
            }
        }

        return resolveDriverVersionWithClassLoader(driverClass, javaClass.classLoader)
    }

    private fun resolveDriverVersionWithClassLoader(
        driverClass: String,
        classLoader: ClassLoader,
    ): String? =
        runCatching {
            val loadedClass = Class.forName(driverClass, false, classLoader)
            val packageVersion = loadedClass.`package`?.implementationVersion?.trim()
            if (!packageVersion.isNullOrBlank()) {
                return@runCatching packageVersion
            }

            val rawDriver = loadedClass.getDeclaredConstructor().newInstance()
            if (rawDriver is Driver) {
                return@runCatching "${rawDriver.majorVersion}.${rawDriver.minorVersion}"
            }

            null
        }.getOrNull()

    private fun externalDriverJarFiles(directoryPath: Path): List<Path> {
        if (!directoryPath.exists() || !directoryPath.isDirectory()) {
            return emptyList()
        }

        return Files
            .walk(directoryPath)
            .use { stream ->
                stream
                    .filter { entry ->
                        Files.isRegularFile(entry) &&
                            entry.fileName.toString().endsWith(".jar", ignoreCase = true)
                    }.toList()
            }
    }

    private fun registerExternalDriver(driverDescriptor: DriverDescriptor) {
        if (registeredExternalDrivers.contains(driverDescriptor.driverId)) {
            return
        }

        val jarFiles = resolveJarFilesForDriver(driverDescriptor)
        if (jarFiles.isEmpty()) {
            throw DriverNotAvailableException(
                "No driver jars found for '${driverDescriptor.driverId}'. Upload the required driver jar first.",
            )
        }

        val urlClassLoader =
            URLClassLoader(
                jarFiles.map { jar -> jar.toUri().toURL() }.toTypedArray(),
                javaClass.classLoader,
            )
        val rawDriver =
            runCatching {
                val driverClass = Class.forName(driverDescriptor.driverClass, true, urlClassLoader)
                driverClass.getDeclaredConstructor().newInstance()
            }.getOrElse { ex ->
                urlClassLoader.close()
                throw DriverNotAvailableException(
                    "Unable to initialize driver '${driverDescriptor.driverClass}': ${ex.message}",
                )
            }

        if (rawDriver !is Driver) {
            urlClassLoader.close()
            throw DriverNotAvailableException(
                "Loaded class '${driverDescriptor.driverClass}' is not a JDBC driver implementation.",
            )
        }

        runCatching {
            DriverManager.registerDriver(DriverShim(rawDriver))
        }.getOrElse { ex ->
            urlClassLoader.close()
            throw DriverNotAvailableException(
                "Failed to register driver '${driverDescriptor.driverId}': ${ex.message}",
            )
        }

        externalDriverClassLoaders[driverDescriptor.driverId]?.close()
        externalDriverClassLoaders[driverDescriptor.driverId] = urlClassLoader
        registeredExternalDrivers.add(driverDescriptor.driverId)
    }

    private fun resolveJarFilesForDriver(driverDescriptor: DriverDescriptor): List<Path> {
        if (driverDescriptor.source == "uploaded") {
            return uploadedDrivers[driverDescriptor.driverId]?.let { registration ->
                listOf(registration.jarPath)
            } ?: emptyList()
        }

        return externalDriverJarFiles(Path.of(externalDriverDirectory()))
    }

    private fun normalizeDriverId(value: String): String {
        val normalized =
            value
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')

        if (normalized.isBlank()) {
            throw IllegalArgumentException("Driver id must contain alphanumeric characters.")
        }

        return normalized
    }

    private fun sanitizeFileName(value: String): String =
        value
            .trim()
            .ifBlank { "driver.jar" }
            .replace(Regex("[^A-Za-z0-9._-]"), "-")

    private class DriverShim(
        private val delegate: Driver,
    ) : Driver {
        override fun connect(
            url: String?,
            info: Properties?,
        ): Connection? = delegate.connect(url, info)

        override fun acceptsURL(url: String?): Boolean = delegate.acceptsURL(url)

        override fun getPropertyInfo(
            url: String?,
            info: Properties?,
        ): Array<DriverPropertyInfo> = delegate.getPropertyInfo(url, info)

        override fun getMajorVersion(): Int = delegate.majorVersion

        override fun getMinorVersion(): Int = delegate.minorVersion

        override fun jdbcCompliant(): Boolean = delegate.jdbcCompliant()

        override fun getParentLogger(): Logger =
            runCatching {
                delegate.parentLogger
            }.getOrElse {
                if (it is SQLFeatureNotSupportedException) {
                    Logger.getLogger(Logger.GLOBAL_LOGGER_NAME)
                } else {
                    throw it
                }
            }
    }
}
