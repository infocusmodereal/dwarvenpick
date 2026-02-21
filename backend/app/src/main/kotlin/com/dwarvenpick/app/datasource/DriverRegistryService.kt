package com.dwarvenpick.app.datasource

import org.springframework.stereotype.Service
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.SQLFeatureNotSupportedException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

data class DriverDescriptor(
    val driverId: String,
    val engine: DatasourceEngine,
    val driverClass: String,
    val source: String,
    val description: String,
    val available: Boolean,
    val message: String,
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

    private val builtInDrivers =
        listOf(
            DriverDescriptor(
                driverId = "postgres-default",
                engine = DatasourceEngine.POSTGRESQL,
                driverClass = "org.postgresql.Driver",
                source = "built-in",
                description = "PostgreSQL JDBC driver",
                available = classExistsOnClasspath("org.postgresql.Driver"),
                message = "PostgreSQL driver availability checked from classpath.",
            ),
            DriverDescriptor(
                driverId = "mysql-default",
                engine = DatasourceEngine.MYSQL,
                driverClass = "com.mysql.cj.jdbc.Driver",
                source = "built-in",
                description = "MySQL Connector/J",
                available = classExistsOnClasspath("com.mysql.cj.jdbc.Driver"),
                message = "MySQL driver availability checked from classpath.",
            ),
            DriverDescriptor(
                driverId = "mariadb-default",
                engine = DatasourceEngine.MARIADB,
                driverClass = "org.mariadb.jdbc.Driver",
                source = "built-in",
                description = "MariaDB JDBC driver",
                available = classExistsOnClasspath("org.mariadb.jdbc.Driver"),
                message = "MariaDB driver availability checked from classpath.",
            ),
            DriverDescriptor(
                driverId = "trino-default",
                engine = DatasourceEngine.TRINO,
                driverClass = "io.trino.jdbc.TrinoDriver",
                source = "built-in",
                description = "Trino JDBC driver",
                available = classExistsOnClasspath("io.trino.jdbc.TrinoDriver"),
                message = "Trino driver availability checked from classpath.",
            ),
            DriverDescriptor(
                driverId = "starrocks-mysql",
                engine = DatasourceEngine.STARROCKS,
                driverClass = "com.mysql.cj.jdbc.Driver",
                source = "built-in",
                description = "StarRocks via MySQL protocol (MySQL Connector/J)",
                available = classExistsOnClasspath("com.mysql.cj.jdbc.Driver"),
                message = "Recommended default for StarRocks in this scaffold.",
            ),
            DriverDescriptor(
                driverId = "starrocks-mariadb",
                engine = DatasourceEngine.STARROCKS,
                driverClass = "org.mariadb.jdbc.Driver",
                source = "built-in",
                description = "StarRocks via MySQL protocol (MariaDB JDBC driver)",
                available = classExistsOnClasspath("org.mariadb.jdbc.Driver"),
                message = "Alternative strategy for MySQL-protocol compatibility.",
            ),
        )

    @Synchronized
    fun ensureDriverReady(driverDescriptor: DriverDescriptor) {
        if (driverDescriptor.source != "external") {
            return
        }

        if (!driverDescriptor.available) {
            throw DriverNotAvailableException(
                buildString {
                    append("Driver '${driverDescriptor.driverId}' is unavailable. ")
                    append(driverDescriptor.message)
                },
            )
        }

        registerExternalDriver(driverDescriptor)
    }

    fun listDrivers(engine: DatasourceEngine? = null): List<DriverDescriptor> {
        val allDescriptors = builtInDrivers + verticaExternalDescriptors()
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

    fun externalDriverDirectory(): String = driverRegistryProperties.externalDir

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
        val urls = jars.map { jar -> jar.toUri().toURL() }.toTypedArray()
        return URLClassLoader(urls, javaClass.classLoader).use { classLoader ->
            runCatching {
                Class.forName(driverClass, false, classLoader)
                true
            }.getOrElse { false }
        }
    }

    private fun externalDriverJarFiles(directoryPath: Path): List<Path> {
        if (!directoryPath.exists() || !directoryPath.isDirectory()) {
            return emptyList()
        }

        return directoryPath
            .listDirectoryEntries()
            .filter { entry ->
                Files.isRegularFile(entry) && entry.extension.equals("jar", ignoreCase = true)
            }
    }

    private fun registerExternalDriver(driverDescriptor: DriverDescriptor) {
        if (registeredExternalDrivers.contains(driverDescriptor.driverId)) {
            return
        }

        val externalDirPath = Path.of(externalDriverDirectory())
        val jarFiles = externalDriverJarFiles(externalDirPath)
        if (jarFiles.isEmpty()) {
            throw DriverNotAvailableException(
                "No driver jars found in '$externalDirPath'. Mount the required driver jar first.",
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
                    "Unable to initialize driver '${driverDescriptor.driverClass}' from '$externalDirPath': ${ex.message}",
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
                "Failed to register external driver '${driverDescriptor.driverId}': ${ex.message}",
            )
        }

        externalDriverClassLoaders[driverDescriptor.driverId]?.close()
        externalDriverClassLoaders[driverDescriptor.driverId] = urlClassLoader
        registeredExternalDrivers.add(driverDescriptor.driverId)
    }

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
