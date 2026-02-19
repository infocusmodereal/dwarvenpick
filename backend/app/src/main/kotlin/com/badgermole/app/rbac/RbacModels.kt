package com.badgermole.app.rbac

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive

data class GroupResponse(
    val id: String,
    val name: String,
    val description: String?,
    val members: Set<String>,
)

data class CreateGroupRequest(
    @field:NotBlank(message = "Group name is required.")
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
    val maxRowsPerQuery: Int?,
    val maxRuntimeSeconds: Int?,
    val concurrencyLimit: Int?,
    val credentialProfile: String,
)

data class UpsertDatasourceAccessRequest(
    val canQuery: Boolean = true,
    val canExport: Boolean = false,
    @field:Positive(message = "maxRowsPerQuery must be positive.")
    val maxRowsPerQuery: Int? = null,
    @field:Positive(message = "maxRuntimeSeconds must be positive.")
    val maxRuntimeSeconds: Int? = null,
    @field:Positive(message = "concurrencyLimit must be positive.")
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
