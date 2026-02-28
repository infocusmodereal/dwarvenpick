package com.dwarvenpick.app.datasource

import com.dwarvenpick.app.rbac.CreateGroupRequest
import com.dwarvenpick.app.rbac.RbacService
import com.dwarvenpick.app.rbac.UpsertDatasourceAccessRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

@Component
class ConnectionsConfigBootstrap(
    private val connectionsConfigProperties: ConnectionsConfigProperties,
    private val datasourceRegistryService: DatasourceRegistryService,
    private val rbacService: RbacService,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(ConnectionsConfigBootstrap::class.java)
    private val configLoader = ConnectionsConfigLoader()

    override fun run(args: ApplicationArguments) {
        val rawPath = connectionsConfigProperties.configPath.trim()
        if (rawPath.isBlank()) {
            return
        }

        val path = Path.of(rawPath)
        require(path.exists() && path.isRegularFile()) {
            "Connections config file '$rawPath' was not found (or is not a regular file)."
        }

        val connections = configLoader.load(path, failOnMissingEnv = connectionsConfigProperties.failOnMissingEnv)
        if (connections.isEmpty()) {
            logger.info("Connections config {} did not define any connections.", path)
            return
        }

        logger.info("Bootstrapping {} connections from {}.", connections.size, path)

        val existingGroups = rbacService.listGroups().map { it.id }.toMutableSet()

        connections.forEach { spec ->
            val datasource =
                datasourceRegistryService.createDatasource(
                    CreateDatasourceRequest(
                        name = spec.name.trim(),
                        engine = spec.engine,
                        host = spec.host.trim(),
                        port = spec.port,
                        database = spec.database?.trim()?.ifBlank { null },
                        driverId = spec.driverId?.trim()?.ifBlank { null },
                        pool = spec.pool,
                        tls = spec.tls,
                        options = spec.options,
                    ),
                )

            spec.credentialProfiles.entries.forEach { (profileId, credential) ->
                datasourceRegistryService.upsertCredentialProfile(
                    datasourceId = datasource.id,
                    profileId = profileId.trim(),
                    request =
                        UpsertCredentialProfileRequest(
                            username = credential.username.trim(),
                            password = credential.password,
                            description = credential.description?.trim()?.ifBlank { null },
                        ),
                )
            }

            val seenAccessKeys = mutableSetOf<String>()
            spec.access.forEach { rule ->
                val groupId = rule.groupId.trim()
                val accessKey = "$groupId::${datasource.id}"
                require(seenAccessKeys.add(accessKey)) {
                    "Duplicate access rule for group '$groupId' and datasource '${datasource.id}'."
                }

                if (groupId !in existingGroups) {
                    rbacService.createGroup(
                        CreateGroupRequest(
                            name = groupId,
                            description = "Bootstrap group for connections config.",
                        ),
                    )
                    existingGroups.add(groupId)
                }

                rbacService.upsertDatasourceAccess(
                    groupId = groupId,
                    datasourceId = datasource.id,
                    request =
                        UpsertDatasourceAccessRequest(
                            canQuery = rule.canQuery,
                            canExport = rule.canExport,
                            readOnly = rule.readOnly,
                            maxRowsPerQuery = rule.maxRowsPerQuery,
                            maxRuntimeSeconds = rule.maxRuntimeSeconds,
                            concurrencyLimit = rule.concurrencyLimit,
                            credentialProfile = rule.credentialProfile.trim(),
                        ),
                )
            }
        }
    }
}
