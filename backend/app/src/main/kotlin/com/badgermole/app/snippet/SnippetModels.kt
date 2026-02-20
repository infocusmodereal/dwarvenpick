package com.badgermole.app.snippet

import jakarta.validation.constraints.NotBlank

data class SnippetResponse(
    val snippetId: String,
    val title: String,
    val sql: String,
    val owner: String,
    val groupId: String?,
    val createdAt: String,
    val updatedAt: String,
)

data class CreateSnippetRequest(
    @field:NotBlank(message = "title is required.")
    val title: String = "",
    @field:NotBlank(message = "sql is required.")
    val sql: String = "",
    val groupId: String? = null,
)

data class UpdateSnippetRequest(
    val title: String? = null,
    val sql: String? = null,
)
