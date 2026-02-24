package com.dwarvenpick.app.rbac

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero

data class GroupResponse(
    val id: String,
    val name: String,
    val description: String?,
    val members: Set<String>,
)

data class CreateGroupRequest(
    @field:NotBlank(message = "Group name is required.")
    @field:Pattern(
        regexp = "^[a-z][a-z0-9.-]*$",
        message = "Group name must start with a letter and contain only lowercase letters, numbers, '.' and '-'.",
    )
    val name: String = "",
    val description: String? = null,
)

data class UpdateGroupRequest(
    val description: String? = null,
)

data class GroupMemberRequest(
    @field:NotBlank(message = "Username is required.")
    val username: String = "",
)

data class DatasourceResponse(
    val id: String,
    val name: String,
    val engine: String,
    val credentialProfiles: Set<String>,
)

data class DatasourceAccessResponse(
    val groupId: String,
    val datasourceId: String,
    val canQuery: Boolean,
    val canExport: Boolean,
    val readOnly: Boolean,
    val maxRowsPerQuery: Int?,
    val maxRuntimeSeconds: Int?,
    val concurrencyLimit: Int?,
    val credentialProfile: String,
)

data class UpsertDatasourceAccessRequest(
    val canQuery: Boolean = true,
    val canExport: Boolean = false,
    val readOnly: Boolean = true,
    @field:PositiveOrZero(message = "maxRowsPerQuery must be positive, or 0 for unlimited.")
    val maxRowsPerQuery: Int? = null,
    @field:PositiveOrZero(message = "maxRuntimeSeconds must be positive, or 0 for unlimited.")
    val maxRuntimeSeconds: Int? = null,
    @field:PositiveOrZero(message = "concurrencyLimit must be positive, or 0 for unlimited.")
    val concurrencyLimit: Int? = null,
    @field:NotBlank(message = "credentialProfile is required.")
    val credentialProfile: String = "",
)

data class QueryExecutionRequest(
    @field:NotBlank(message = "datasourceId is required.")
    val datasourceId: String = "",
    @field:NotBlank(message = "sql is required.")
    val sql: String = "",
)

data class QueryExecutionResponse(
    val executionId: String,
    val datasourceId: String,
    val status: String,
    val message: String,
)
