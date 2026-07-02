package com.dwarvenpick.app.resource

import jakarta.validation.constraints.NotBlank

enum class ResourceScope {
    PRIVATE,
    SHARED,
}

enum class ResourceVersionAction {
    CREATED,
    UPDATED_METADATA,
    UPDATED_CONTENT,
    RESTORED,
    DUPLICATED,
}

data class ResourcePermissionsResponse(
    val canView: Boolean,
    val canEdit: Boolean,
    val canExecute: Boolean,
    val canDelete: Boolean,
    val canShare: Boolean,
)

data class ResourceScriptResponse(
    val resourceId: String,
    val title: String,
    val sql: String,
    val owner: String,
    val scope: ResourceScope,
    val groupId: String?,
    val folderPath: String,
    val datasourceId: String?,
    val tags: List<String>,
    val allowGroupEdit: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val currentRevision: Int,
    val versionCount: Int,
    val permissions: ResourcePermissionsResponse,
)

data class ResourceScriptSummaryResponse(
    val resourceId: String,
    val title: String,
    val sqlPreview: String,
    val sqlLength: Int,
    val owner: String,
    val scope: ResourceScope,
    val groupId: String?,
    val folderPath: String,
    val datasourceId: String?,
    val tags: List<String>,
    val allowGroupEdit: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val currentRevision: Int,
    val versionCount: Int,
    val permissions: ResourcePermissionsResponse,
)

data class ResourceVersionResponse(
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
    val savedAt: String,
)

data class CreateResourceRequest(
    @field:NotBlank(message = "title is required.")
    val title: String = "",
    val sql: String = "",
    val scope: ResourceScope = ResourceScope.PRIVATE,
    val groupId: String? = null,
    val folderPath: String = "",
    val datasourceId: String? = null,
    val tags: List<String> = emptyList(),
    val allowGroupEdit: Boolean = false,
)

data class UpdateResourceMetadataRequest(
    @field:NotBlank(message = "title is required.")
    val title: String = "",
    val sql: String = "",
    val scope: ResourceScope = ResourceScope.PRIVATE,
    val groupId: String? = null,
    val folderPath: String = "",
    val datasourceId: String? = null,
    val tags: List<String> = emptyList(),
    val allowGroupEdit: Boolean = false,
)

data class UpdateResourceContentRequest(
    @field:NotBlank(message = "title is required.")
    val title: String = "",
    val sql: String = "",
    val datasourceId: String? = null,
)

data class DuplicateResourceRequest(
    val title: String? = null,
    val scope: ResourceScope? = null,
    val groupId: String? = null,
    val folderPath: String? = null,
    val datasourceId: String? = null,
    val tags: List<String>? = null,
    val allowGroupEdit: Boolean? = null,
)

data class RestoreResourceVersionRequest(
    val keepCurrentMetadata: Boolean = false,
)
