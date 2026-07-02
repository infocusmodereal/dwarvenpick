package com.dwarvenpick.app.resource

import com.dwarvenpick.app.auth.AuthenticatedUserPrincipal
import com.dwarvenpick.app.datasource.DriverRegistryProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.exists

class ResourceNotFoundException(
    override val message: String,
) : RuntimeException(message)

class ResourceAccessDeniedException(
    override val message: String,
) : RuntimeException(message)

data class ResourceRecord(
    val resourceId: String,
    val owner: String,
    var title: String,
    var sql: String,
    var scope: ResourceScope,
    var groupId: String?,
    var folderPath: String,
    var datasourceId: String?,
    var tags: List<String>,
    var allowGroupEdit: Boolean,
    val createdAt: Instant,
    var updatedAt: Instant,
    var currentRevision: Int = 0,
)

data class ResourceSummaryRecord(
    val resourceId: String,
    val owner: String,
    val title: String,
    val sqlPreview: String,
    val sqlLength: Int,
    val scope: ResourceScope,
    val groupId: String?,
    val folderPath: String,
    val datasourceId: String?,
    val tags: List<String>,
    val allowGroupEdit: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val currentRevision: Int,
    val versionCount: Int,
)

data class ResourceVersionRecord(
    val versionId: String,
    val resourceId: String,
    val revision: Int,
    val title: String,
    val sql: String,
    val scope: ResourceScope,
    val groupId: String?,
    val folderPath: String,
    val datasourceId: String?,
    val tags: List<String>,
    val allowGroupEdit: Boolean,
    val action: ResourceVersionAction,
    val savedBy: String,
    val savedAt: Instant,
)

data class ResourceStorageSnapshot(
    val version: Int = 2,
    val resources: List<ResourceRecord> = emptyList(),
    val versions: Map<String, List<ResourceVersionRecord>> = emptyMap(),
)

private val resourceTitlePattern = Regex("^[A-Za-z0-9][A-Za-z0-9.-]*$")
private val resourceTagPattern = Regex("^[a-z0-9][a-z0-9.-]*$")
private const val DEFAULT_RESOURCE_LIST_LIMIT = 500
private const val MAX_RESOURCE_LIST_LIMIT = 1000

@Service
class ResourceService(
    private val objectMapper: ObjectMapper,
    private val resourceRepository: ResourceRepository,
    driverRegistryProperties: DriverRegistryProperties,
) {
    private val logger = LoggerFactory.getLogger(ResourceService::class.java)
    private val baseDir: Path = Path.of(driverRegistryProperties.externalDir).resolve("resources")
    private val storagePath: Path = baseDir.resolve("scripts.json")

    init {
        migrateLegacyDiskState()
    }

    fun listResources(
        principal: AuthenticatedUserPrincipal,
        scope: String?,
        query: String?,
        groupId: String?,
        datasourceId: String?,
        tag: String?,
        limit: Int?,
        offset: Int?,
    ): List<ResourceScriptSummaryResponse> {
        val normalizedScope = scope?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val normalizedQuery = query?.trim()?.takeIf { it.isNotBlank() }
        val normalizedGroupId = groupId?.trim()?.takeIf { it.isNotBlank() }
        val normalizedDatasourceId = datasourceId?.trim()?.takeIf { it.isNotBlank() }
        val normalizedTag = normalizeTag(tag)
        val scopeFilter =
            when (normalizedScope) {
                null, "all" -> null
                "private" -> ResourceScope.PRIVATE
                "shared" -> ResourceScope.SHARED
                else -> throw IllegalArgumentException("Unsupported resource scope '$scope'.")
            }
        val resolvedLimit = (limit ?: DEFAULT_RESOURCE_LIST_LIMIT).coerceIn(1, MAX_RESOURCE_LIST_LIMIT)
        val resolvedOffset = (offset ?: 0).coerceAtLeast(0)

        return (
            resourceRepository.listResourceSummaries(
                username = principal.username,
                groups = principal.groups,
                systemAdmin = principal.roles.contains("SYSTEM_ADMIN"),
                query = normalizedQuery,
                scope = scopeFilter,
                groupId = normalizedGroupId,
                datasourceId = normalizedDatasourceId,
                tag = normalizedTag,
                limit = resolvedLimit,
                offset = resolvedOffset,
            )
        ).asSequence()
            // SQL is a prefilter; keep the exact Kotlin check so case-insensitive DB collations cannot broaden access.
            .filter { record -> canView(principal, record) }
            .map { record -> record.toSummaryResponse(principal) }
            .toList()
    }

    fun getResource(
        principal: AuthenticatedUserPrincipal,
        resourceId: String,
    ): ResourceScriptResponse {
        val resource = resourceRecord(resourceId)
        enforceCanView(principal, resource)
        return resource.toResponse(principal)
    }

    fun listVersions(
        principal: AuthenticatedUserPrincipal,
        resourceId: String,
    ): List<ResourceVersionResponse> {
        val resource = resourceRecord(resourceId)
        enforceCanView(principal, resource)
        return resourceRepository
            .listVersions(resourceId)
            .sortedByDescending { version -> version.revision }
            .map { version -> version.toResponse() }
    }

    @Synchronized
    fun createResource(
        principal: AuthenticatedUserPrincipal,
        request: CreateResourceRequest,
    ): ResourceScriptResponse {
        val now = Instant.now()
        val normalized = normalizeCreateOrMetadataRequest(principal, request)
        val resource =
            ResourceRecord(
                resourceId = UUID.randomUUID().toString(),
                owner = principal.username,
                title = normalized.title,
                sql = request.sql,
                scope = normalized.scope,
                groupId = normalized.groupId,
                folderPath = normalized.folderPath,
                datasourceId = normalized.datasourceId,
                tags = normalized.tags,
                allowGroupEdit = normalized.allowGroupEdit,
                createdAt = now,
                updatedAt = now,
            )
        appendVersion(resource, principal.username, ResourceVersionAction.CREATED, now)
        resourceRepository.saveResource(resource)
        return resource.toResponse(principal)
    }

    @Synchronized
    fun updateMetadata(
        principal: AuthenticatedUserPrincipal,
        resourceId: String,
        request: UpdateResourceMetadataRequest,
    ): ResourceScriptResponse {
        val resource = resourceRecord(resourceId)
        enforceCanShare(principal, resource)
        val normalized = normalizeCreateOrMetadataRequest(principal, request)
        val now = Instant.now()

        resource.title = normalized.title
        resource.sql = request.sql
        resource.scope = normalized.scope
        resource.groupId = normalized.groupId
        resource.folderPath = normalized.folderPath
        resource.datasourceId = normalized.datasourceId
        resource.tags = normalized.tags
        resource.allowGroupEdit = normalized.allowGroupEdit
        resource.updatedAt = now

        appendVersion(resource, principal.username, ResourceVersionAction.UPDATED_METADATA, now)
        resourceRepository.saveResource(resource)
        return resource.toResponse(principal)
    }

    @Synchronized
    fun updateContent(
        principal: AuthenticatedUserPrincipal,
        resourceId: String,
        request: UpdateResourceContentRequest,
    ): ResourceScriptResponse {
        val resource = resourceRecord(resourceId)
        enforceCanEdit(principal, resource)
        val normalizedTitle = normalizeResourceTitle(request.title)

        val now = Instant.now()
        resource.title = normalizedTitle
        resource.sql = request.sql
        resource.datasourceId = request.datasourceId?.trim()?.ifBlank { null }
        resource.updatedAt = now

        appendVersion(resource, principal.username, ResourceVersionAction.UPDATED_CONTENT, now)
        resourceRepository.saveResource(resource)
        return resource.toResponse(principal)
    }

    @Synchronized
    fun restoreVersion(
        principal: AuthenticatedUserPrincipal,
        resourceId: String,
        versionId: String,
        request: RestoreResourceVersionRequest,
    ): ResourceScriptResponse {
        val resource = resourceRecord(resourceId)
        enforceCanShare(principal, resource)
        val version = versionRecord(resourceId, versionId)
        val now = Instant.now()

        resource.title = version.title
        resource.sql = version.sql
        resource.datasourceId = version.datasourceId
        if (!request.keepCurrentMetadata) {
            resource.scope = version.scope
            resource.groupId = version.groupId
            resource.folderPath = version.folderPath
            resource.tags = version.tags
            resource.allowGroupEdit = version.allowGroupEdit
        }
        resource.updatedAt = now

        appendVersion(resource, principal.username, ResourceVersionAction.RESTORED, now)
        resourceRepository.saveResource(resource)
        return resource.toResponse(principal)
    }

    @Synchronized
    fun duplicateResource(
        principal: AuthenticatedUserPrincipal,
        resourceId: String,
        request: DuplicateResourceRequest,
    ): ResourceScriptResponse {
        val source = resourceRecord(resourceId)
        enforceCanView(principal, source)

        val requestedScope =
            request.scope ?: if (canShare(principal, source)) source.scope else ResourceScope.PRIVATE
        val requestedGroupId =
            when (requestedScope) {
                ResourceScope.PRIVATE -> null
                ResourceScope.SHARED -> request.groupId?.trim()?.ifBlank { null } ?: source.groupId
            }

        val normalized =
            normalizeResourceMetadata(
                principal = principal,
                title = request.title?.trim()?.ifBlank { null } ?: buildDuplicateTitle(source.title),
                scope = requestedScope,
                groupId = requestedGroupId,
                folderPath = request.folderPath ?: source.folderPath,
                datasourceId = request.datasourceId ?: source.datasourceId,
                tags = request.tags ?: source.tags,
                allowGroupEdit = request.allowGroupEdit ?: source.allowGroupEdit,
            )

        val now = Instant.now()
        val copy =
            ResourceRecord(
                resourceId = UUID.randomUUID().toString(),
                owner = principal.username,
                title = normalized.title,
                sql = source.sql,
                scope = normalized.scope,
                groupId = normalized.groupId,
                folderPath = normalized.folderPath,
                datasourceId = normalized.datasourceId,
                tags = normalized.tags,
                allowGroupEdit = normalized.allowGroupEdit,
                createdAt = now,
                updatedAt = now,
            )
        appendVersion(copy, principal.username, ResourceVersionAction.DUPLICATED, now)
        resourceRepository.saveResource(copy)
        return copy.toResponse(principal)
    }

    @Synchronized
    fun deleteResource(
        principal: AuthenticatedUserPrincipal,
        resourceId: String,
    ): Boolean {
        val resource = resourceRecord(resourceId)
        enforceCanDelete(principal, resource)
        return resourceRepository.deleteResource(resourceId)
    }

    private fun resourceRecord(resourceId: String): ResourceRecord =
        resourceRepository.findResource(resourceId)
            ?: throw ResourceNotFoundException("Resource '$resourceId' was not found.")

    private fun versionRecord(
        resourceId: String,
        versionId: String,
    ): ResourceVersionRecord =
        resourceRepository.findVersion(resourceId, versionId)
            ?: throw ResourceNotFoundException(
                "Resource version '$versionId' was not found for resource '$resourceId'.",
            )

    private fun appendVersion(
        resource: ResourceRecord,
        actor: String,
        action: ResourceVersionAction,
        timestamp: Instant = Instant.now(),
    ) {
        val revision = resource.currentRevision + 1
        resourceRepository.saveVersion(
            ResourceVersionRecord(
                versionId = UUID.randomUUID().toString(),
                resourceId = resource.resourceId,
                revision = revision,
                title = resource.title,
                sql = resource.sql,
                scope = resource.scope,
                groupId = resource.groupId,
                folderPath = resource.folderPath,
                datasourceId = resource.datasourceId,
                tags = resource.tags,
                allowGroupEdit = resource.allowGroupEdit,
                action = action,
                savedBy = actor,
                savedAt = timestamp,
            ),
        )
        resource.currentRevision = revision
    }

    private fun normalizeCreateOrMetadataRequest(
        principal: AuthenticatedUserPrincipal,
        request: CreateResourceRequest,
    ): NormalizedResourceMetadata =
        normalizeResourceMetadata(
            principal = principal,
            title = request.title,
            scope = request.scope,
            groupId = request.groupId,
            folderPath = request.folderPath,
            datasourceId = request.datasourceId,
            tags = request.tags,
            allowGroupEdit = request.allowGroupEdit,
        )

    private fun normalizeCreateOrMetadataRequest(
        principal: AuthenticatedUserPrincipal,
        request: UpdateResourceMetadataRequest,
    ): NormalizedResourceMetadata =
        normalizeResourceMetadata(
            principal = principal,
            title = request.title,
            scope = request.scope,
            groupId = request.groupId,
            folderPath = request.folderPath,
            datasourceId = request.datasourceId,
            tags = request.tags,
            allowGroupEdit = request.allowGroupEdit,
        )

    private data class NormalizedResourceMetadata(
        val title: String,
        val scope: ResourceScope,
        val groupId: String?,
        val folderPath: String,
        val datasourceId: String?,
        val tags: List<String>,
        val allowGroupEdit: Boolean,
    )

    private fun normalizeResourceMetadata(
        principal: AuthenticatedUserPrincipal,
        title: String,
        scope: ResourceScope,
        groupId: String?,
        folderPath: String,
        datasourceId: String?,
        tags: List<String>,
        allowGroupEdit: Boolean,
    ): NormalizedResourceMetadata {
        val normalizedTitle = normalizeResourceTitle(title)

        val normalizedGroupId =
            when (scope) {
                ResourceScope.PRIVATE -> null
                ResourceScope.SHARED -> {
                    val resolved =
                        groupId?.trim()?.ifBlank { null }
                            ?: throw IllegalArgumentException("Shared resources require a group.")
                    if (!principal.roles.contains("SYSTEM_ADMIN") && resolved !in principal.groups) {
                        throw ResourceAccessDeniedException(
                            "Sharing denied. Join group '$resolved' or save the resource as private.",
                        )
                    }
                    resolved
                }
            }

        val normalizedFolderPath =
            folderPath
                .split('/')
                .map { segment -> segment.trim() }
                .filter { segment -> segment.isNotBlank() }
                .onEach { segment ->
                    if (segment == "." || segment == "..") {
                        throw IllegalArgumentException("Folder path cannot contain '.' or '..' segments.")
                    }
                    if (!resourceTitlePattern.matches(segment)) {
                        throw IllegalArgumentException(
                            "Folder names must start with a letter or number and only use letters, numbers, dots or hyphens.",
                        )
                    }
                }.joinToString("/")

        return NormalizedResourceMetadata(
            title = normalizedTitle,
            scope = scope,
            groupId = normalizedGroupId,
            folderPath = normalizedFolderPath,
            datasourceId = datasourceId?.trim()?.ifBlank { null },
            tags = normalizeTags(tags),
            allowGroupEdit = scope == ResourceScope.SHARED && allowGroupEdit,
        )
    }

    private fun normalizeTags(rawTags: List<String>): List<String> =
        rawTags
            .asSequence()
            .mapNotNull { normalizeTag(it) }
            .distinct()
            .sorted()
            .toList()

    private fun normalizeTag(rawTag: String?): String? {
        val normalized = rawTag?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (!resourceTagPattern.matches(normalized)) {
            throw IllegalArgumentException(
                "Tags must start with a letter or number and only use lowercase letters, numbers, dots or hyphens.",
            )
        }
        return normalized
    }

    private fun normalizeResourceTitle(rawTitle: String): String {
        val normalizedTitle = rawTitle.trim()
        if (normalizedTitle.isBlank()) {
            throw IllegalArgumentException("Resource title is required.")
        }
        if (!resourceTitlePattern.matches(normalizedTitle)) {
            throw IllegalArgumentException(
                "Resource titles must start with a letter or number and only use letters, numbers, dots or hyphens.",
            )
        }
        return normalizedTitle
    }

    private fun buildDuplicateTitle(sourceTitle: String): String {
        val normalized =
            sourceTitle
                .trim()
                .replace(Regex("[_\\s]+"), "-")
                .replace(Regex("[^A-Za-z0-9.-]+"), "-")
                .replace(Regex("^[^A-Za-z0-9]+"), "")
                .replace(Regex("-{2,}"), "-")
                .replace(Regex("\\.{2,}"), ".")
                .replace(Regex("[.-]+$"), "")

        val candidate = if (normalized.isBlank()) "script" else normalized
        val duplicateTitle = if (candidate.endsWith("-copy")) candidate else "$candidate-copy"
        return if (resourceTitlePattern.matches(duplicateTitle)) {
            duplicateTitle
        } else {
            "script-copy"
        }
    }

    private fun canView(
        principal: AuthenticatedUserPrincipal,
        resource: ResourceRecord,
    ): Boolean =
        principal.roles.contains("SYSTEM_ADMIN") ||
            resource.owner == principal.username ||
            (
                resource.scope == ResourceScope.SHARED &&
                    resource.groupId != null &&
                    resource.groupId in principal.groups
            )

    private fun canView(
        principal: AuthenticatedUserPrincipal,
        resource: ResourceSummaryRecord,
    ): Boolean =
        principal.roles.contains("SYSTEM_ADMIN") ||
            resource.owner == principal.username ||
            (
                resource.scope == ResourceScope.SHARED &&
                    resource.groupId != null &&
                    resource.groupId in principal.groups
            )

    private fun canEdit(
        principal: AuthenticatedUserPrincipal,
        resource: ResourceRecord,
    ): Boolean =
        principal.roles.contains("SYSTEM_ADMIN") ||
            resource.owner == principal.username ||
            (
                resource.scope == ResourceScope.SHARED &&
                    resource.allowGroupEdit &&
                    resource.groupId != null &&
                    resource.groupId in principal.groups
            )

    private fun canDelete(
        principal: AuthenticatedUserPrincipal,
        resource: ResourceRecord,
    ): Boolean = principal.roles.contains("SYSTEM_ADMIN") || resource.owner == principal.username

    private fun canShare(
        principal: AuthenticatedUserPrincipal,
        resource: ResourceRecord,
    ): Boolean = principal.roles.contains("SYSTEM_ADMIN") || resource.owner == principal.username

    private fun canEdit(
        principal: AuthenticatedUserPrincipal,
        resource: ResourceSummaryRecord,
    ): Boolean =
        principal.roles.contains("SYSTEM_ADMIN") ||
            resource.owner == principal.username ||
            (
                resource.scope == ResourceScope.SHARED &&
                    resource.allowGroupEdit &&
                    resource.groupId != null &&
                    resource.groupId in principal.groups
            )

    private fun canDelete(
        principal: AuthenticatedUserPrincipal,
        resource: ResourceSummaryRecord,
    ): Boolean = principal.roles.contains("SYSTEM_ADMIN") || resource.owner == principal.username

    private fun canShare(
        principal: AuthenticatedUserPrincipal,
        resource: ResourceSummaryRecord,
    ): Boolean = principal.roles.contains("SYSTEM_ADMIN") || resource.owner == principal.username

    private fun enforceCanView(
        principal: AuthenticatedUserPrincipal,
        resource: ResourceRecord,
    ) {
        if (!canView(principal, resource)) {
            throw ResourceAccessDeniedException("Resource view denied for '${resource.resourceId}'.")
        }
    }

    private fun enforceCanEdit(
        principal: AuthenticatedUserPrincipal,
        resource: ResourceRecord,
    ) {
        if (!canEdit(principal, resource)) {
            throw ResourceAccessDeniedException("Resource edit denied for '${resource.resourceId}'.")
        }
    }

    private fun enforceCanDelete(
        principal: AuthenticatedUserPrincipal,
        resource: ResourceRecord,
    ) {
        if (!canDelete(principal, resource)) {
            throw ResourceAccessDeniedException("Resource delete denied for '${resource.resourceId}'.")
        }
    }

    private fun enforceCanShare(
        principal: AuthenticatedUserPrincipal,
        resource: ResourceRecord,
    ) {
        if (!canShare(principal, resource)) {
            throw ResourceAccessDeniedException("Resource share denied for '${resource.resourceId}'.")
        }
    }

    private fun ResourceRecord.toResponse(principal: AuthenticatedUserPrincipal): ResourceScriptResponse =
        ResourceScriptResponse(
            resourceId = resourceId,
            title = title,
            sql = sql,
            owner = owner,
            scope = scope,
            groupId = groupId,
            folderPath = folderPath,
            datasourceId = datasourceId,
            tags = tags,
            allowGroupEdit = allowGroupEdit,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
            currentRevision = currentRevision,
            versionCount = resourceRepository.countVersions(resourceId),
            permissions =
                ResourcePermissionsResponse(
                    canView = canView(principal, this),
                    canEdit = canEdit(principal, this),
                    canExecute = canView(principal, this),
                    canDelete = canDelete(principal, this),
                    canShare = canShare(principal, this),
                ),
        )

    private fun ResourceSummaryRecord.toSummaryResponse(principal: AuthenticatedUserPrincipal): ResourceScriptSummaryResponse =
        ResourceScriptSummaryResponse(
            resourceId = resourceId,
            title = title,
            sqlPreview = sqlPreview,
            sqlLength = sqlLength,
            owner = owner,
            scope = scope,
            groupId = groupId,
            folderPath = folderPath,
            datasourceId = datasourceId,
            tags = tags,
            allowGroupEdit = allowGroupEdit,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
            currentRevision = currentRevision,
            versionCount = versionCount,
            permissions =
                ResourcePermissionsResponse(
                    canView = canView(principal, this),
                    canEdit = canEdit(principal, this),
                    canExecute = canView(principal, this),
                    canDelete = canDelete(principal, this),
                    canShare = canShare(principal, this),
                ),
        )

    private fun ResourceVersionRecord.toResponse(): ResourceVersionResponse =
        ResourceVersionResponse(
            versionId = versionId,
            resourceId = resourceId,
            revision = revision,
            title = title,
            sql = sql,
            scope = scope,
            groupId = groupId,
            folderPath = folderPath,
            datasourceId = datasourceId,
            tags = tags,
            allowGroupEdit = allowGroupEdit,
            action = action,
            savedBy = savedBy,
            savedAt = savedAt.toString(),
        )

    fun clear() = resourceRepository.clear()

    private fun migrateLegacyDiskState() {
        try {
            if (!storagePath.exists()) {
                return
            }

            val snapshot = objectMapper.readValue<ResourceStorageSnapshot>(storagePath.toFile())
            val resources = snapshot.resources.toMutableList()
            val versionsByResource =
                snapshot.versions
                    .mapValues { (_, versions) -> versions.sortedBy { version -> version.revision }.toMutableList() }
                    .toMutableMap()

            resources.forEach { resource ->
                val versions = versionsByResource.getOrPut(resource.resourceId) { mutableListOf() }
                if (versions.isEmpty()) {
                    val revision = resource.currentRevision.coerceAtLeast(1)
                    versions.add(
                        ResourceVersionRecord(
                            versionId = UUID.randomUUID().toString(),
                            resourceId = resource.resourceId,
                            revision = revision,
                            title = resource.title,
                            sql = resource.sql,
                            scope = resource.scope,
                            groupId = resource.groupId,
                            folderPath = resource.folderPath,
                            datasourceId = resource.datasourceId,
                            tags = resource.tags,
                            allowGroupEdit = resource.allowGroupEdit,
                            action = ResourceVersionAction.CREATED,
                            savedBy = resource.owner,
                            savedAt = resource.updatedAt,
                        ),
                    )
                }

                val latestRevision = versions.maxOf { version -> version.revision }
                if (resource.currentRevision != latestRevision) {
                    resource.currentRevision = latestRevision
                }
            }

            val imported =
                resourceRepository.importSnapshotIfEmpty(
                    ResourceStorageSnapshot(
                        resources = resources,
                        versions = versionsByResource,
                    ),
                )

            if (imported) {
                logger.info(
                    "Migrated {} resource manager scripts and {} saved versions from {} into application database.",
                    resources.size,
                    versionsByResource.values.sumOf { versions -> versions.size },
                    storagePath,
                )
            }
        } catch (exception: Exception) {
            logger.error("Failed to migrate resource manager state from {}", storagePath, exception)
        }
    }
}
