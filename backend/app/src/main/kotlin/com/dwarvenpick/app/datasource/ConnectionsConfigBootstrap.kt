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
        }

        logger.info("Bootstrapping {} connections from {}.", connections.size, path)

        val existingGroups = rbacService.listGroups().map { it.id }.toMutableSet()
        val desiredDatasourceIds = mutableSetOf<String>()
        val desiredCredentialProfiles = linkedMapOf<String, MutableSet<String>>()
        val desiredAccessKeys = mutableSetOf<String>()

        connections.forEach { spec ->
            val datasource =
                datasourceRegistryService.upsertManagedDatasource(
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
                    source = "config",
                    allowUnresolvedHost = true,
                )
            desiredDatasourceIds.add(datasource.id)
            val datasourceProfiles = desiredCredentialProfiles.computeIfAbsent(datasource.id) { mutableSetOf() }

            spec.credentialProfiles.entries.forEach { (profileId, credential) ->
                val normalizedProfileId = profileId.trim()
                datasourceProfiles.add(normalizedProfileId)
                datasourceRegistryService.upsertCredentialProfile(
                    datasourceId = datasource.id,
                    profileId = normalizedProfileId,
                    request =
                        UpsertCredentialProfileRequest(
                            username = credential.username.trim(),
                            password = credential.password,
                            description = credential.description?.trim()?.ifBlank { null },
                            sysadmin = credential.sysadmin,
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
                desiredAccessKeys.add(accessKey)

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

        if (connectionsConfigProperties.authoritative) {
            reconcileAuthoritativeConfig(
                desiredDatasourceIds = desiredDatasourceIds,
                desiredCredentialProfiles = desiredCredentialProfiles,
                desiredAccessKeys = desiredAccessKeys,
            )
        }
    }

    private fun reconcileAuthoritativeConfig(
        desiredDatasourceIds: Set<String>,
        desiredCredentialProfiles: Map<String, Set<String>>,
        desiredAccessKeys: Set<String>,
    ) {
        val configManagedDatasources = datasourceRegistryService.listDatasourcesBySource("config")
        val configManagedDatasourceIds = configManagedDatasources.map { datasource -> datasource.id }.toSet()
        val removedDatasourceIds = configManagedDatasourceIds - desiredDatasourceIds
        val governedDatasourceIds = configManagedDatasourceIds + desiredDatasourceIds

        rbacService.listDatasourceAccess(null).forEach { access ->
            val accessKey = "${access.groupId}::${access.datasourceId}"
            if (access.datasourceId in governedDatasourceIds && accessKey !in desiredAccessKeys) {
                rbacService.deleteDatasourceAccess(access.groupId, access.datasourceId)
                logger.info(
                    "Removed stale config-managed access mapping group={} datasource={}.",
                    access.groupId,
                    access.datasourceId,
                )
            }
        }

        configManagedDatasources
            .filter { datasource -> datasource.id in desiredDatasourceIds }
            .forEach { datasource ->
                val desiredProfiles = desiredCredentialProfiles[datasource.id].orEmpty()
                val staleProfiles = datasource.credentialProfiles.keys - desiredProfiles
                staleProfiles.forEach { profileId ->
                    datasourceRegistryService.deleteCredentialProfile(datasource.id, profileId)
                    logger.info(
                        "Removed stale config-managed credential profile datasource={} profile={}.",
                        datasource.id,
                        profileId,
                    )
                }
            }

        removedDatasourceIds.forEach { datasourceId ->
            datasourceRegistryService.deleteDatasource(datasourceId)
            logger.info("Removed stale config-managed datasource={}.", datasourceId)
        }
    }
}
