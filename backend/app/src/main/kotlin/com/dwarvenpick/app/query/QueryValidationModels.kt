package com.dwarvenpick.app.query

import jakarta.validation.constraints.NotBlank

data class QueryValidationRequest(
    @field:NotBlank(message = "datasourceId is required.")
    val datasourceId: String = "",
    @field:NotBlank(message = "sql is required.")
    val sql: String = "",
    /**
     * Optional credential profile override.
     *
     * When omitted, the backend resolves the effective credential profile via RBAC rules (or SYSTEM_ADMIN defaults).
     */
    val credentialProfile: String? = null,
    /**
     * Optional default schema/database to apply for unqualified object names while validating.
     */
    val defaultSchema: String? = null,
)

data class QueryValidationResponse(
    val valid: Boolean,
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
    val position: Int? = null,
)
