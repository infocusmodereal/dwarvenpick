package com.dwarvenpick.app.snippet

import com.dwarvenpick.app.auth.AuthenticatedUserPrincipal
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

class SnippetNotFoundException(
    override val message: String,
) : RuntimeException(message)

class SnippetAccessDeniedException(
    override val message: String,
) : RuntimeException(message)

data class SnippetRecord(
    val snippetId: String,
    val owner: String,
    var groupId: String?,
    var title: String,
    var sql: String,
    val createdAt: Instant,
    var updatedAt: Instant,
)

data class SnippetSummaryRecord(
    val snippetId: String,
    val owner: String,
    val groupId: String?,
    val title: String,
    val sqlPreview: String,
    val sqlLength: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

private const val DEFAULT_SNIPPET_LIST_LIMIT = 1000
private const val MAX_SNIPPET_LIST_LIMIT = 1000
private const val MAX_SNIPPET_REGEX_SCAN_LIMIT = 1000

@Service
class SnippetService(
    private val snippetRepository: SnippetRepository,
) {
    fun listSnippets(
        principal: AuthenticatedUserPrincipal,
        scope: String?,
        title: String?,
        titleMatch: String?,
        groupId: String?,
        limit: Int? = null,
        offset: Int? = null,
    ): List<SnippetSummaryResponse> {
        val normalizedScope = scope?.trim()?.lowercase()
        val includePersonal = normalizedScope == null || normalizedScope == "all" || normalizedScope == "personal"
        val includeGroup = normalizedScope == null || normalizedScope == "all" || normalizedScope == "group"
        val normalizedGroupId = groupId?.trim()?.takeIf { it.isNotBlank() }
        val titleFilter = title?.trim()?.takeIf { it.isNotBlank() }
        val resolvedTitleMatch = titleMatch?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val resolvedLimit = (limit ?: DEFAULT_SNIPPET_LIST_LIMIT).coerceIn(1, MAX_SNIPPET_LIST_LIMIT)
        val resolvedOffset = (offset ?: 0).coerceAtLeast(0)
        val titleRegex =
            when {
                titleFilter == null -> null
                resolvedTitleMatch == "regex" -> compileTitleRegex(titleFilter, fallbackIgnoreCase = true)
                else -> parseRegexFilterOrNull(titleFilter)
            }
        val exactTitle = if (titleFilter != null && titleRegex == null) titleFilter.lowercase() else null
        val repositoryLimit = if (titleRegex == null) resolvedLimit else MAX_SNIPPET_REGEX_SCAN_LIMIT + 1
        val repositoryOffset = if (titleRegex == null) resolvedOffset else 0

        val summaries =
            snippetRepository
                .listSummaries(
                    username = principal.username,
                    groups = principal.groups,
                    systemAdmin = principal.roles.contains("SYSTEM_ADMIN"),
                    includePersonal = includePersonal,
                    includeGroup = includeGroup,
                    groupId = normalizedGroupId,
                    exactTitle = exactTitle,
                    limit = repositoryLimit,
                    offset = repositoryOffset,
                ).filter { record ->
                    // SQL is a prefilter; keep the exact Kotlin check so case-insensitive DB collations cannot broaden access.
                    (includePersonal && record.owner == principal.username) ||
                        (includeGroup && canAccessGroupSnippet(principal, record))
                }
        if (titleRegex != null && summaries.size > MAX_SNIPPET_REGEX_SCAN_LIMIT) {
            throw IllegalArgumentException(
                "Snippet regex filters scan at most $MAX_SNIPPET_REGEX_SCAN_LIMIT snippets. Narrow the scope, group, or title filter.",
            )
        }

        return summaries
            .asSequence()
            .filter { record ->
                titleRegex == null || titleRegex.containsMatchIn(record.title)
            }.drop(if (titleRegex == null) 0 else resolvedOffset)
            .take(if (titleRegex == null) Int.MAX_VALUE else resolvedLimit)
            .map { record -> record.toSummaryResponse() }
            .toList()
    }

    fun getSnippet(
        principal: AuthenticatedUserPrincipal,
        snippetId: String,
    ): SnippetResponse {
        val snippet = snippetRepository.find(snippetId) ?: throw SnippetNotFoundException("Snippet '$snippetId' was not found.")
        if (!canViewSnippet(principal, snippet)) {
            throw SnippetAccessDeniedException("Snippet view denied for '$snippetId'.")
        }
        return snippet.toResponse()
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
        snippetRepository.save(snippet)
        return snippet.toResponse()
    }

    fun updateSnippet(
        principal: AuthenticatedUserPrincipal,
        snippetId: String,
        request: UpdateSnippetRequest,
    ): SnippetResponse {
        val snippet = snippetRepository.find(snippetId) ?: throw SnippetNotFoundException("Snippet '$snippetId' was not found.")

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

        snippetRepository.save(snippet)
        return snippet.toResponse()
    }

    fun deleteSnippet(
        principal: AuthenticatedUserPrincipal,
        snippetId: String,
    ): Boolean {
        val snippet = snippetRepository.find(snippetId) ?: throw SnippetNotFoundException("Snippet '$snippetId' was not found.")

        if (!canEditSnippet(principal, snippet)) {
            throw SnippetAccessDeniedException("Snippet delete denied for '$snippetId'.")
        }

        return snippetRepository.delete(snippetId)
    }

    fun clear() = snippetRepository.clear()

    private fun canAccessGroupSnippet(
        principal: AuthenticatedUserPrincipal,
        snippet: SnippetSummaryRecord,
    ): Boolean =
        snippet.groupId != null &&
            (principal.roles.contains("SYSTEM_ADMIN") || snippet.groupId in principal.groups)

    private fun canViewSnippet(
        principal: AuthenticatedUserPrincipal,
        snippet: SnippetRecord,
    ): Boolean =
        snippet.owner == principal.username ||
            (
                snippet.groupId != null &&
                    (principal.roles.contains("SYSTEM_ADMIN") || snippet.groupId in principal.groups)
            )

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

    private fun parseRegexFilterOrNull(rawFilter: String): Regex? {
        if (rawFilter.length < 2 || !rawFilter.startsWith('/')) {
            return null
        }
        val closingSlashIndex = rawFilter.lastIndexOf('/')
        if (closingSlashIndex <= 0) {
            return null
        }

        return compileTitleRegex(rawFilter, fallbackIgnoreCase = false)
    }

    private fun compileTitleRegex(
        rawFilter: String,
        fallbackIgnoreCase: Boolean,
    ): Regex {
        val startsAsSlashRegex = rawFilter.length >= 2 && rawFilter.startsWith('/')
        val closingSlashIndex = rawFilter.lastIndexOf('/')
        val hasSlashDelimitedPattern = startsAsSlashRegex && closingSlashIndex > 0
        val pattern =
            if (hasSlashDelimitedPattern) {
                rawFilter.substring(1, closingSlashIndex)
            } else {
                rawFilter
            }
        if (pattern.isBlank()) {
            throw IllegalArgumentException("Snippet title regex cannot be empty.")
        }
        val options = mutableSetOf<RegexOption>()
        if (fallbackIgnoreCase) {
            options.add(RegexOption.IGNORE_CASE)
        }
        val flags =
            if (hasSlashDelimitedPattern) {
                rawFilter.substring(closingSlashIndex + 1)
            } else {
                ""
            }
        flags.forEach { flag ->
            when (flag) {
                'i' -> options.add(RegexOption.IGNORE_CASE)
                'm' -> options.add(RegexOption.MULTILINE)
                's' -> options.add(RegexOption.DOT_MATCHES_ALL)
                else -> throw IllegalArgumentException("Unsupported regex flag '$flag' for snippet title filter.")
            }
        }

        return try {
            Regex(pattern, options)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("Snippet title regex is invalid: ${exception.message}")
        }
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

    private fun SnippetSummaryRecord.toSummaryResponse(): SnippetSummaryResponse =
        SnippetSummaryResponse(
            snippetId = snippetId,
            title = title,
            sqlPreview = sqlPreview,
            sqlLength = sqlLength,
            owner = owner,
            groupId = groupId,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )
}
