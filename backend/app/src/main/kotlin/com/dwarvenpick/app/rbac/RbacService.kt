package com.dwarvenpick.app.rbac

import com.dwarvenpick.app.auth.AuthenticatedUserPrincipal
import com.dwarvenpick.app.auth.DisabledUserException
import com.dwarvenpick.app.auth.UserAccountService
import com.dwarvenpick.app.auth.UserNotFoundException
import com.dwarvenpick.app.datasource.CatalogDatasourceEntry
import com.dwarvenpick.app.datasource.DatasourceRegistryService
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class GroupNotFoundException(
    override val message: String,
) : RuntimeException(message)

class DatasourceNotFoundException(
    override val message: String,
) : RuntimeException(message)

class QueryAccessDeniedException(
    override val message: String,
) : RuntimeException(message)

data class QueryAccessPolicy(
    val credentialProfile: String,
    val readOnly: Boolean,
    val maxRowsPerQuery: Int,
    val maxRuntimeSeconds: Int,
    val concurrencyLimit: Int,
)

private data class GroupRecord(
    val id: String,
    val name: String,
    var description: String?,
    val members: MutableSet<String>,
)

private data class DatasourceAccessRecord(
    val groupId: String,
    val datasourceId: String,
    var canQuery: Boolean,
    var canExport: Boolean,
    var readOnly: Boolean,
    var maxRowsPerQuery: Int?,
    var maxRuntimeSeconds: Int?,
    var concurrencyLimit: Int?,
    var credentialProfile: String,
)

@Service
class RbacService(
    private val userAccountService: UserAccountService,
    private val datasourceRegistryService: DatasourceRegistryService,
) {
    private val groups = ConcurrentHashMap<String, GroupRecord>()
    private val datasourceAccess = ConcurrentHashMap<String, DatasourceAccessRecord>()

    @PostConstruct
    fun initialize() {
        resetState()
    }

    @Synchronized
    fun resetState() {
        groups.clear()
        datasourceAccess.clear()
        datasourceRegistryService.resetState()
        seedGroups()
        seedAccessMappings()
    }

    fun listGroups(): List<GroupResponse> =
        groups.values
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
            .map { group -> group.toResponse() }

    fun createGroup(request: CreateGroupRequest): GroupResponse {
        val groupId = slugify(request.name)
        if (groups.containsKey(groupId)) {
            throw IllegalArgumentException("Group '$groupId' already exists.")
        }

        groups[groupId] =
            GroupRecord(
                id = groupId,
                name = request.name.trim(),
                description = request.description?.trim()?.ifBlank { null },
                members = linkedSetOf(),
            )

        return groups.getValue(groupId).toResponse()
    }

    fun updateGroup(
        groupId: String,
        request: UpdateGroupRequest,
    ): GroupResponse {
        val group =
            groups[groupId]
                ?: throw GroupNotFoundException("Group '$groupId' was not found.")

        group.description = request.description?.trim()?.ifBlank { null }
        return group.toResponse()
    }

    fun addMember(
        groupId: String,
        username: String,
    ): GroupResponse {
        val group =
            groups[groupId]
                ?: throw GroupNotFoundException("Group '$groupId' was not found.")

        val normalizedUsername = username.trim()
        if (normalizedUsername.isBlank()) {
            throw IllegalArgumentException("Username is required.")
        }

        try {
            userAccountService.addGroupMembership(normalizedUsername, groupId)
        } catch (ex: UserNotFoundException) {
            throw ex
        } catch (ex: DisabledUserException) {
            throw ex
        }

        group.members.add(normalizedUsername)
        return group.toResponse()
    }

    fun removeMember(
        groupId: String,
        username: String,
    ): GroupResponse {
        val group =
            groups[groupId]
                ?: throw GroupNotFoundException("Group '$groupId' was not found.")

        val normalizedUsername = username.trim()
        if (normalizedUsername.isBlank()) {
            throw IllegalArgumentException("Username is required.")
        }

        try {
            userAccountService.removeGroupMembership(normalizedUsername, groupId)
        } catch (ex: UserNotFoundException) {
            throw ex
        } catch (ex: DisabledUserException) {
            throw ex
        }

        group.members.remove(normalizedUsername)
        return group.toResponse()
    }

    fun listDatasourceCatalog(): List<DatasourceResponse> =
        datasourceRegistryService.listCatalogEntries().map { catalog -> catalog.toResponse() }

    fun listDatasourceAccess(groupId: String?): List<DatasourceAccessResponse> =
        datasourceAccess.values
            .asSequence()
            .filter { access -> groupId == null || access.groupId == groupId }
            .sortedWith(compareBy({ access -> access.groupId }, { access -> access.datasourceId }))
            .map { access -> access.toResponse() }
            .toList()

    fun upsertDatasourceAccess(
        groupId: String,
        datasourceId: String,
        request: UpsertDatasourceAccessRequest,
    ): DatasourceAccessResponse {
        if (!groups.containsKey(groupId)) {
            throw GroupNotFoundException("Group '$groupId' was not found.")
        }

        if (!datasourceRegistryService.hasDatasource(datasourceId)) {
            throw DatasourceNotFoundException("Datasource '$datasourceId' was not found.")
        }

        val credentialProfile = request.credentialProfile.trim()
        if (credentialProfile.isBlank()) {
            throw IllegalArgumentException("credentialProfile is required.")
        }

        if (!datasourceRegistryService.credentialProfilesForDatasource(datasourceId).contains(credentialProfile)) {
            throw IllegalArgumentException(
                "credentialProfile '$credentialProfile' is not available for datasource '$datasourceId'.",
            )
        }

        val key = accessKey(groupId, datasourceId)
        val record =
            datasourceAccess.computeIfAbsent(key) {
                DatasourceAccessRecord(
                    groupId = groupId,
                    datasourceId = datasourceId,
                    canQuery = request.canQuery,
                    canExport = request.canExport,
                    readOnly = request.readOnly,
                    maxRowsPerQuery = request.maxRowsPerQuery,
                    maxRuntimeSeconds = request.maxRuntimeSeconds,
                    concurrencyLimit = request.concurrencyLimit,
                    credentialProfile = credentialProfile,
                )
            }

        record.canQuery = request.canQuery
        record.canExport = request.canExport
        record.readOnly = request.readOnly
        record.maxRowsPerQuery = request.maxRowsPerQuery
        record.maxRuntimeSeconds = request.maxRuntimeSeconds
        record.concurrencyLimit = request.concurrencyLimit
        record.credentialProfile = credentialProfile

        return record.toResponse()
    }

    fun deleteDatasourceAccess(
        groupId: String,
        datasourceId: String,
    ): Boolean {
        val key = accessKey(groupId, datasourceId)
        return datasourceAccess.remove(key) != null
    }

    fun listPermittedDatasources(principal: AuthenticatedUserPrincipal): List<DatasourceResponse> {
        if (principal.roles.contains("SYSTEM_ADMIN")) {
            return listDatasourceCatalog()
        }

        val allowedIds =
            datasourceAccess.values
                .asSequence()
                .filter { access -> access.groupId in principal.groups && access.canQuery }
                .map { access -> access.datasourceId }
                .toSet()

        return datasourceRegistryService
            .listCatalogEntries()
            .asSequence()
            .filter { datasource -> datasource.id in allowedIds }
            .map { datasource -> datasource.toResponse() }
            .toList()
    }

    fun canUserQuery(
        principal: AuthenticatedUserPrincipal,
        datasourceId: String,
    ): Boolean {
        if (!datasourceRegistryService.hasDatasource(datasourceId)) {
            throw DatasourceNotFoundException("Datasource '$datasourceId' was not found.")
        }

        if (principal.roles.contains("SYSTEM_ADMIN")) {
            return true
        }

        return datasourceAccess.values.any { access ->
            access.datasourceId == datasourceId &&
                access.groupId in principal.groups &&
                access.canQuery
        }
    }

    fun canUserExport(
        principal: AuthenticatedUserPrincipal,
        datasourceId: String,
    ): Boolean {
        if (!datasourceRegistryService.hasDatasource(datasourceId)) {
            throw DatasourceNotFoundException("Datasource '$datasourceId' was not found.")
        }

        if (principal.roles.contains("SYSTEM_ADMIN")) {
            return true
        }

        return datasourceAccess.values.any { access ->
            access.datasourceId == datasourceId &&
                access.groupId in principal.groups &&
                access.canExport
        }
    }

    fun resolveQueryAccessPolicy(
        principal: AuthenticatedUserPrincipal,
        datasourceId: String,
    ): QueryAccessPolicy {
        if (!datasourceRegistryService.hasDatasource(datasourceId)) {
            throw DatasourceNotFoundException("Datasource '$datasourceId' was not found.")
        }

        val matchingAccessRules =
            datasourceAccess.values
                .asSequence()
                .filter { access ->
                    access.datasourceId == datasourceId &&
                        access.canQuery &&
                        (principal.roles.contains("SYSTEM_ADMIN") || access.groupId in principal.groups)
                }.sortedWith(compareBy({ access -> access.groupId }, { access -> access.credentialProfile }))
                .toList()

        if (matchingAccessRules.isEmpty()) {
            if (principal.roles.contains("SYSTEM_ADMIN")) {
                return QueryAccessPolicy(
                    credentialProfile = "admin-ro",
                    readOnly = false,
                    maxRowsPerQuery = 5000,
                    maxRuntimeSeconds = 300,
                    concurrencyLimit = 5,
                )
            }

            throw QueryAccessDeniedException("Datasource access denied for query execution.")
        }

        val availableProfiles = datasourceRegistryService.credentialProfilesForDatasource(datasourceId)
        val selectedCredentialProfile =
            matchingAccessRules
                .map { access -> access.credentialProfile }
                .firstOrNull { credentialProfile -> credentialProfile in availableProfiles }
                ?: throw QueryAccessDeniedException(
                    "No valid credential profile is configured for datasource '$datasourceId'.",
                )

        return QueryAccessPolicy(
            credentialProfile = selectedCredentialProfile,
            readOnly = matchingAccessRules.all { access -> access.readOnly },
            maxRowsPerQuery = matchingAccessRules.mapNotNull { access -> access.maxRowsPerQuery }.minOrNull() ?: 5000,
            maxRuntimeSeconds =
                matchingAccessRules.mapNotNull { access -> access.maxRuntimeSeconds }.minOrNull() ?: 300,
            concurrencyLimit = matchingAccessRules.mapNotNull { access -> access.concurrencyLimit }.minOrNull() ?: 5,
        )
    }

    private fun seedGroups() {
        val adminGroupId = "platform-admins"
        groups[adminGroupId] =
            GroupRecord(
                id = adminGroupId,
                name = "Platform Admins",
                description = "System administrators with governance permissions.",
                members = linkedSetOf("admin"),
            )
        userAccountService.addGroupMembership("admin", adminGroupId)

        val analystsGroupId = "analytics-users"
        groups[analystsGroupId] =
            GroupRecord(
                id = analystsGroupId,
                name = "Analytics Users",
                description = "Analysts with warehouse query access.",
                members = linkedSetOf("analyst"),
            )
        userAccountService.addGroupMembership("analyst", analystsGroupId)
    }

    private fun seedAccessMappings() {
        val allDatasourceIds = datasourceRegistryService.listCatalogEntries().map { datasource -> datasource.id }
        allDatasourceIds.forEach { datasourceId ->
            datasourceAccess[accessKey("platform-admins", datasourceId)] =
                DatasourceAccessRecord(
                    groupId = "platform-admins",
                    datasourceId = datasourceId,
                    canQuery = true,
                    canExport = true,
                    readOnly = false,
                    maxRowsPerQuery = 5000,
                    maxRuntimeSeconds = 300,
                    concurrencyLimit = 5,
                    credentialProfile = "admin-ro",
                )
        }

        datasourceAccess[accessKey("analytics-users", "trino-warehouse")] =
            DatasourceAccessRecord(
                groupId = "analytics-users",
                datasourceId = "trino-warehouse",
                canQuery = true,
                canExport = false,
                readOnly = true,
                maxRowsPerQuery = 2000,
                maxRuntimeSeconds = 180,
                concurrencyLimit = 2,
                credentialProfile = "analyst-ro",
            )
    }

    private fun slugify(input: String): String {
        val slug =
            input
                .trim()
                .lowercase(Locale.getDefault())
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')

        if (slug.isBlank()) {
            throw IllegalArgumentException("Group name must contain alphanumeric characters.")
        }

        return slug
    }

    private fun accessKey(
        groupId: String,
        datasourceId: String,
    ): String = "$groupId::$datasourceId"

    private fun GroupRecord.toResponse(): GroupResponse =
        GroupResponse(
            id = id,
            name = name,
            description = description,
            members = members.toSet(),
        )

    private fun DatasourceAccessRecord.toResponse(): DatasourceAccessResponse =
        DatasourceAccessResponse(
            groupId = groupId,
            datasourceId = datasourceId,
            canQuery = canQuery,
            canExport = canExport,
            readOnly = readOnly,
            maxRowsPerQuery = maxRowsPerQuery,
            maxRuntimeSeconds = maxRuntimeSeconds,
            concurrencyLimit = concurrencyLimit,
            credentialProfile = credentialProfile,
        )

    private fun CatalogDatasourceEntry.toResponse(): DatasourceResponse =
        DatasourceResponse(
            id = id,
            name = name,
            engine = engine.name,
            credentialProfiles = credentialProfiles,
        )
}
