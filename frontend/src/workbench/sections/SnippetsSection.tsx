import { useState } from 'react';
import type { SnippetResponse, SnippetSummaryResponse } from '../types';
import { IconButton } from '../components/WorkbenchIcons';

const snippetListLimit = 1000;
const snippetSqlPreviewLength = 240;

type SnippetsSectionProps = {
    hidden: boolean;
    snippetScope: 'all' | 'personal' | 'group';
    onSnippetScopeChange: (scope: 'all' | 'personal' | 'group') => void;
    snippetTitleInput: string;
    onSnippetTitleChange: (value: string) => void;
    snippetGroupInput: string;
    onSnippetGroupChange: (value: string) => void;
    snippetGroupOptions: string[];
    loadingSnippets: boolean;
    onRefresh: () => void;
    snippetError: string;
    snippets: SnippetSummaryResponse[];
    onOpenSnippet: (snippet: SnippetResponse, run: boolean) => void;
    onDeleteSnippet: (snippetId: string) => void;
};

export default function SnippetsSection({
    hidden,
    snippetScope,
    onSnippetScopeChange,
    snippetTitleInput,
    onSnippetTitleChange,
    snippetGroupInput,
    onSnippetGroupChange,
    snippetGroupOptions,
    loadingSnippets,
    onRefresh,
    snippetError,
    snippets,
    onOpenSnippet,
    onDeleteSnippet
}: SnippetsSectionProps) {
    const [openingSnippetId, setOpeningSnippetId] = useState('');
    const [snippetDetailError, setSnippetDetailError] = useState('');
    const snippetListMayBeCapped = snippets.length >= snippetListLimit;
    const openSnippet = async (snippetId: string, run: boolean) => {
        setOpeningSnippetId(snippetId);
        setSnippetDetailError('');
        try {
            const response = await fetch(`/api/snippets/${snippetId}`, {
                method: 'GET',
                credentials: 'include'
            });
            if (!response.ok) {
                throw new Error((await response.text()) || 'Failed to load snippet.');
            }
            onOpenSnippet((await response.json()) as SnippetResponse, run);
        } catch (error) {
            setSnippetDetailError(
                error instanceof Error ? error.message : 'Failed to load snippet.'
            );
        } finally {
            setOpeningSnippetId('');
        }
    };

    return (
        <section className="panel snippets-panel" hidden={hidden}>
            <div className="history-filters">
                <div className="filter-field">
                    <label htmlFor="snippet-scope">Scope</label>
                    <div className="select-wrap">
                        <select
                            id="snippet-scope"
                            value={snippetScope}
                            onChange={(event) =>
                                onSnippetScopeChange(
                                    event.target.value as 'all' | 'personal' | 'group'
                                )
                            }
                        >
                            <option value="all">All visible</option>
                            <option value="personal">Personal</option>
                            <option value="group">Group shared</option>
                        </select>
                    </div>
                </div>

                <div className="filter-field">
                    <label htmlFor="snippet-title">Title / Regex</label>
                    <input
                        id="snippet-title"
                        value={snippetTitleInput}
                        onChange={(event) => onSnippetTitleChange(event.target.value)}
                        placeholder="Daily health query or /^daily/i"
                    />
                </div>

                <div className="filter-field">
                    <label htmlFor="snippet-group-id">Group</label>
                    <div className="select-wrap">
                        <select
                            id="snippet-group-id"
                            value={snippetGroupInput}
                            onChange={(event) => onSnippetGroupChange(event.target.value)}
                        >
                            <option value="">All groups</option>
                            {snippetGroupOptions.map((groupId) => (
                                <option key={`snippet-group-${groupId}`} value={groupId}>
                                    {groupId}
                                </option>
                            ))}
                        </select>
                    </div>
                </div>
            </div>

            <div className="row toolbar-actions">
                <IconButton
                    icon="refresh"
                    title={loadingSnippets ? 'Refreshing snippets...' : 'Refresh snippets'}
                    onClick={onRefresh}
                    disabled={loadingSnippets}
                />
            </div>

            {snippetError ? (
                <p className="form-error" role="alert">
                    {snippetError}
                </p>
            ) : null}

            {snippetDetailError ? (
                <p className="form-error" role="alert">
                    {snippetDetailError}
                </p>
            ) : null}

            {snippetListMayBeCapped ? (
                <p className="resource-list-cap-note">
                    Showing newest {snippetListLimit.toLocaleString()} snippets. Narrow filters to
                    find older matches.
                </p>
            ) : null}

            <div className="history-table-wrap">
                <table className="result-table history-table">
                    <thead>
                        <tr>
                            <th>Updated</th>
                            <th>Title</th>
                            <th>Owner</th>
                            <th>Group</th>
                            <th>SQL</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {snippets.length === 0 ? (
                            <tr>
                                <td colSpan={6}>No snippets available for this scope.</td>
                            </tr>
                        ) : (
                            snippets.map((snippet) => (
                                <tr key={`snippet-${snippet.snippetId}`}>
                                    <td>{new Date(snippet.updatedAt).toLocaleString()}</td>
                                    <td>{snippet.title}</td>
                                    <td>{snippet.owner}</td>
                                    <td>{snippet.groupId ?? '-'}</td>
                                    <td className="history-query">
                                        {snippet.sqlPreview}
                                        {snippet.sqlLength > snippetSqlPreviewLength
                                            ? ` (${snippet.sqlLength.toLocaleString()} chars)`
                                            : ''}
                                    </td>
                                    <td className="history-actions">
                                        <button
                                            type="button"
                                            className="chip"
                                            onClick={() =>
                                                void openSnippet(snippet.snippetId, false)
                                            }
                                            disabled={openingSnippetId === snippet.snippetId}
                                        >
                                            Open
                                        </button>
                                        <button
                                            type="button"
                                            onClick={() =>
                                                void openSnippet(snippet.snippetId, true)
                                            }
                                            disabled={openingSnippetId === snippet.snippetId}
                                        >
                                            Run
                                        </button>
                                        <button
                                            type="button"
                                            className="danger-button"
                                            onClick={() => onDeleteSnippet(snippet.snippetId)}
                                        >
                                            Delete
                                        </button>
                                    </td>
                                </tr>
                            ))
                        )}
                    </tbody>
                </table>
            </div>
        </section>
    );
}
