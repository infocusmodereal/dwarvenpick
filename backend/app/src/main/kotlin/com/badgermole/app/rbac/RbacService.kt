package com.badgermole.app.rbac

import com.badgermole.app.auth.AuthenticatedUserPrincipal
import com.badgermole.app.auth.DisabledUserException
import com.badgermole.app.auth.UserAccountService
import com.badgermole.app.auth.UserNotFoundException
import jakarta.annotation.PostConstruct
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Service

class GroupNotFoundException(
    override val message: String,
) : RuntimeException(message)

class DatasourceNotFoundException(
    override val message: String,
) : RuntimeException(message)

private data class GroupRecord(
    val id: String,
    val name: String,
    var description: String?,
    val members: MutableSet<String>,
)

private data class DatasourceRecord(
    val id: String,
    val name: String,
    val engine: String,
    val credentialProfiles: Set<String>,
)

private data class DatasourceAccessRecord(
    val groupId: String,
    val datasourceId: String,
    var canQuery: Boolean,
    var canExport: Boolean,
    var maxRowsPerQuery: Int?,
    var maxRuntimeSeconds: Int?,
    var concurrencyLimit: Int?,
    var credentialProfile: String,
)

@Service
class RbacService(
    private val userAccountService: UserAccountService,
) {
    private val groups = ConcurrentHashMap<String, GroupRecord>()
    private val datasources = ConcurrentHashMap<String, DatasourceRecord>()
    private val datasourceAccess = ConcurrentHashMap<String, DatasourceAccessRecord>()

    @PostConstruct
    fun initialize() {
        resetState()
    }

    @Synchronized
    fun resetState() {
        groups.clear()
        datasources.clear()
        datasourceAccess.clear()
        seedGroups()
        seedDatasources()
        seedAccessMappings()
    }

    fun listGroups(): List<GroupResponse> =
        groups.values
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
            .map { it.toResponse() }

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

    fun updateGroup(groupId: String, request: UpdateGroupRequest): GroupResponse {
        val group =
            groups[groupId]
                ?: throw GroupNotFoundException("Group '$groupId' was not found.")

        group.description = request.description?.trim()?.ifBlank { null }
        return group.toResponse()
    }

    fun addMember(groupId: String, username: String): GroupResponse {
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

    fun removeMember(groupId: String, username: String): GroupResponse {
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
        datasources.values
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
            .map { datasource -> datasource.toResponse() }

    fun listDatasourceAccess(groupId: String?): List<DatasourceAccessResponse> =
        datasourceAccess.values
            .asSequence()
            .filter { access -> groupId == null || access.groupId == groupId }
            .sortedWith(compareBy({ it.groupId }, { it.datasourceId }))
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

        val datasource =
            datasources[datasourceId]
                ?: throw DatasourceNotFoundException("Datasource '$datasourceId' was not found.")

        val credentialProfile = request.credentialProfile.trim()
        if (credentialProfile.isBlank()) {
            throw IllegalArgumentException("credentialProfile is required.")
        }

        if (!datasource.credentialProfiles.contains(credentialProfile)) {
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
                    maxRowsPerQuery = request.maxRowsPerQuery,
                    maxRuntimeSeconds = request.maxRuntimeSeconds,
                    concurrencyLimit = request.concurrencyLimit,
                    credentialProfile = credentialProfile,
                )
            }

        record.canQuery = request.canQuery
        record.canExport = request.canExport
        record.maxRowsPerQuery = request.maxRowsPerQuery
        record.maxRuntimeSeconds = request.maxRuntimeSeconds
        record.concurrencyLimit = request.concurrencyLimit
        record.credentialProfile = credentialProfile

        return record.toResponse()
    }

    fun deleteDatasourceAccess(groupId: String, datasourceId: String): Boolean {
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

        return datasources.values
            .asSequence()
            .filter { datasource -> datasource.id in allowedIds }
            .sortedBy { datasource -> datasource.name.lowercase(Locale.getDefault()) }
            .map { datasource -> datasource.toResponse() }
            .toList()
    }

    fun canUserQuery(principal: AuthenticatedUserPrincipal, datasourceId: String): Boolean {
        if (!datasources.containsKey(datasourceId)) {
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

    private fun seedDatasources() {
        val seededDatasources =
            listOf(
                DatasourceRecord(
                    id = "postgres-core",
                    name = "PostgreSQL Core",
                    engine = "PostgreSQL",
                    credentialProfiles = setOf("admin-ro", "analyst-ro"),
                ),
                DatasourceRecord(
                    id = "mysql-orders",
                    name = "MySQL Orders",
                    engine = "MySQL",
                    credentialProfiles = setOf("admin-ro", "ops-ro"),
                ),
                DatasourceRecord(
                    id = "trino-warehouse",
                    name = "Trino Warehouse",
                    engine = "Trino",
                    credentialProfiles = setOf("admin-ro", "analyst-ro"),
                ),
            )

        seededDatasources.forEach { datasource ->
            datasources[datasource.id] = datasource
        }
    }

    private fun seedAccessMappings() {
        val allDatasourceIds = datasources.keys.toList()
        allDatasourceIds.forEach { datasourceId ->
            datasourceAccess[accessKey("platform-admins", datasourceId)] =
                DatasourceAccessRecord(
                    groupId = "platform-admins",
                    datasourceId = datasourceId,
                    canQuery = true,
                    canExport = true,
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

    private fun accessKey(groupId: String, datasourceId: String): String = "$groupId::$datasourceId"

    private fun GroupRecord.toResponse(): GroupResponse =
        GroupResponse(
            id = id,
            name = name,
            description = description,
            members = members.toSet(),
        )

    private fun DatasourceRecord.toResponse(): DatasourceResponse =
        DatasourceResponse(
            id = id,
            name = name,
            engine = engine,
            credentialProfiles = credentialProfiles,
        )

    private fun DatasourceAccessRecord.toResponse(): DatasourceAccessResponse =
        DatasourceAccessResponse(
            groupId = groupId,
            datasourceId = datasourceId,
            canQuery = canQuery,
            canExport = canExport,
            maxRowsPerQuery = maxRowsPerQuery,
            maxRuntimeSeconds = maxRuntimeSeconds,
            concurrencyLimit = concurrencyLimit,
            credentialProfile = credentialProfile,
        )
}
