package com.badgermole.app.snippet

import com.badgermole.app.auth.AuthenticatedUserPrincipal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Service

class SnippetNotFoundException(
    override val message: String,
) : RuntimeException(message)

class SnippetAccessDeniedException(
    override val message: String,
) : RuntimeException(message)

private data class SnippetRecord(
    val snippetId: String,
    val owner: String,
    var groupId: String?,
    var title: String,
    var sql: String,
    val createdAt: Instant,
    var updatedAt: Instant,
)

@Service
class SnippetService {
    private val snippets = ConcurrentHashMap<String, SnippetRecord>()

    fun listSnippets(
        principal: AuthenticatedUserPrincipal,
        scope: String?,
    ): List<SnippetResponse> {
        val normalizedScope = scope?.trim()?.lowercase()
        val includePersonal = normalizedScope == null || normalizedScope == "all" || normalizedScope == "personal"
        val includeGroup = normalizedScope == null || normalizedScope == "all" || normalizedScope == "group"

        return snippets.values
            .asSequence()
            .filter { record ->
                (includePersonal && record.owner == principal.username) ||
                    (includeGroup && canAccessGroupSnippet(principal, record))
            }
            .sortedByDescending { record -> record.updatedAt }
            .map { record -> record.toResponse() }
            .toList()
    }

    fun createSnippet(
        principal: AuthenticatedUserPrincipal,
        request: CreateSnippetRequest,
    ): SnippetResponse {
        val normalizedGroupId = request.groupId?.trim()?.ifBlank { null }
        if (normalizedGroupId != null && !canManageGroupSnippet(principal, normalizedGroupId)) {
            throw SnippetAccessDeniedException(
                "Group snippet creation denied. Join group '$normalizedGroupId' or use a personal snippet.",
            )
        }

        val now = Instant.now()
        val snippetId = UUID.randomUUID().toString()
        val snippet =
            SnippetRecord(
                snippetId = snippetId,
                owner = principal.username,
                groupId = normalizedGroupId,
                title = request.title.trim(),
                sql = request.sql,
                createdAt = now,
                updatedAt = now,
            )
        snippets[snippetId] = snippet
        return snippet.toResponse()
    }

    fun updateSnippet(
        principal: AuthenticatedUserPrincipal,
        snippetId: String,
        request: UpdateSnippetRequest,
    ): SnippetResponse {
        val snippet =
            snippets[snippetId]
                ?: throw SnippetNotFoundException("Snippet '$snippetId' was not found.")

        if (!canEditSnippet(principal, snippet)) {
            throw SnippetAccessDeniedException("Snippet update denied for '$snippetId'.")
        }

        request.title?.trim()?.takeIf { title -> title.isNotBlank() }?.let { title ->
            snippet.title = title
        }
        request.sql?.let { sql ->
            snippet.sql = sql
        }
        snippet.updatedAt = Instant.now()

        return snippet.toResponse()
    }

    fun deleteSnippet(
        principal: AuthenticatedUserPrincipal,
        snippetId: String,
    ): Boolean {
        val snippet =
            snippets[snippetId]
                ?: throw SnippetNotFoundException("Snippet '$snippetId' was not found.")

        if (!canEditSnippet(principal, snippet)) {
            throw SnippetAccessDeniedException("Snippet delete denied for '$snippetId'.")
        }

        return snippets.remove(snippetId) != null
    }

    private fun canAccessGroupSnippet(
        principal: AuthenticatedUserPrincipal,
        snippet: SnippetRecord,
    ): Boolean =
        snippet.groupId != null &&
            (principal.roles.contains("SYSTEM_ADMIN") || snippet.groupId in principal.groups)

    private fun canManageGroupSnippet(
        principal: AuthenticatedUserPrincipal,
        groupId: String,
    ): Boolean = principal.roles.contains("SYSTEM_ADMIN") || groupId in principal.groups

    private fun canEditSnippet(
        principal: AuthenticatedUserPrincipal,
        snippet: SnippetRecord,
    ): Boolean {
        if (principal.roles.contains("SYSTEM_ADMIN")) {
            return true
        }
        if (snippet.owner == principal.username) {
            return true
        }
        return snippet.groupId != null && snippet.groupId in principal.groups
    }

    private fun SnippetRecord.toResponse(): SnippetResponse =
        SnippetResponse(
            snippetId = snippetId,
            title = title,
            sql = sql,
            owner = owner,
            groupId = groupId,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )
}
