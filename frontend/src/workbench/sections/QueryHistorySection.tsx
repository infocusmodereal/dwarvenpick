import type { CatalogDatasourceResponse, QueryHistoryEntryResponse } from '../types';
import { toStatusToneClass } from '../utils';
import { IconButton, IconGlyph, LabeledActionButton } from '../components/WorkbenchIcons';
import HistoryQueryHoverCard from '../components/HistoryQueryHoverCard';
import InlineNotice from '../components/InlineNotice';
import { copyQueryText, getQueryHoverCard, type QueryHoverCard } from '../historyQueryPreview';
import { Fragment, useCallback, useEffect, useRef, useState } from 'react';
import type { FocusEvent as ReactFocusEvent, MouseEvent as ReactMouseEvent } from 'react';

type QueryHistorySectionProps = {
    hidden: boolean;
    visibleDatasources: CatalogDatasourceResponse[];
    datasourceFilter: string;
    onDatasourceFilterChange: (value: string) => void;
    statusFilter: string;
    onStatusFilterChange: (value: string) => void;
    fromFilter: string;
    onFromFilterChange: (value: string) => void;
    toFilter: string;
    onToFilterChange: (value: string) => void;
    loadingQueryHistory: boolean;
    errorMessage: string;
    onRefresh: () => void;
    sortOrder: 'newest' | 'oldest';
    onToggleSortOrder: () => void;
    onClearFilters: () => void;
    entries: QueryHistoryEntryResponse[];
    pageIndex: number;
    pageSize: number;
    hasNextPage: boolean;
    onPageIndexChange: (value: number) => void;
    onPageSizeChange: (value: number) => void;
    onOpenEntry: (entry: QueryHistoryEntryResponse, rerun: boolean) => void;
};

const formatCsvCell = (value: string | null | undefined): string => {
    if (value === null || value === undefined) {
        return '';
    }

    const raw = String(value);
    const requiresQuotes =
        raw.includes(',') || raw.includes('"') || raw.includes('\n') || raw.includes('\r');

    if (!requiresQuotes) {
        return raw;
    }

    return `"${raw.replace(/"/g, '""')}"`;
};

const toCsv = (headers: string[], rows: Array<Array<string | null | undefined>>): string => {
    const headerRow = headers.map((value) => formatCsvCell(value)).join(',') + '\n';
    const bodyRows = rows.map((row) => row.map((value) => formatCsvCell(value)).join(',') + '\n');
    return headerRow + bodyRows.join('');
};

const downloadCsv = (fileName: string, csv: string) => {
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const objectUrl = window.URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = fileName;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    window.URL.revokeObjectURL(objectUrl);
};

export default function QueryHistorySection({
    hidden,
    visibleDatasources,
    datasourceFilter,
    onDatasourceFilterChange,
    statusFilter,
    onStatusFilterChange,
    fromFilter,
    onFromFilterChange,
    toFilter,
    onToFilterChange,
    loadingQueryHistory,
    errorMessage,
    onRefresh,
    sortOrder,
    onToggleSortOrder,
    onClearFilters,
    entries,
    pageIndex,
    pageSize,
    hasNextPage,
    onPageIndexChange,
    onPageSizeChange,
    onOpenEntry
}: QueryHistorySectionProps) {
    const [search, setSearch] = useState('');
    const [expandedId, setExpandedId] = useState('');
    const filteredEntries = entries.filter((entry) =>
        [
            entry.datasourceId,
            entry.status,
            entry.justification,
            entry.queryTextRedacted ? '' : entry.queryText
        ]
            .join(' ')
            .toLowerCase()
            .includes(search.trim().toLowerCase())
    );
    useEffect(() => {
        setSearch('');
        setExpandedId('');
    }, [pageIndex, pageSize, datasourceFilter, statusFilter, fromFilter, toFilter]);
    const [copiedExecutionId, setCopiedExecutionId] = useState('');
    const [copyError, setCopyError] = useState('');
    const [hoveredQuery, setHoveredQuery] = useState<QueryHoverCard | null>(null);
    const hoverHideTimeoutRef = useRef<number | null>(null);

    const clearHoverHideTimeout = useCallback(() => {
        if (hoverHideTimeoutRef.current === null) {
            return;
        }

        window.clearTimeout(hoverHideTimeoutRef.current);
        hoverHideTimeoutRef.current = null;
    }, []);

    const showQueryHoverCard = useCallback(
        (trigger: HTMLElement, executionId: string, queryText: string) => {
            clearHoverHideTimeout();
            setHoveredQuery(getQueryHoverCard(trigger, executionId, queryText));
        },
        [clearHoverHideTimeout]
    );

    const scheduleQueryHoverCardHide = useCallback(() => {
        clearHoverHideTimeout();
        hoverHideTimeoutRef.current = window.setTimeout(() => {
            setHoveredQuery(null);
            hoverHideTimeoutRef.current = null;
        }, 160);
    }, [clearHoverHideTimeout]);

    const handleCopyQuery = useCallback(async (executionId: string, queryText: string) => {
        try {
            await copyQueryText(queryText);
            setCopyError('');
            setCopiedExecutionId(executionId);
        } catch {
            setCopiedExecutionId('');
            setCopyError('Unable to copy query text.');
        }
    }, []);

    useEffect(() => {
        if (!copiedExecutionId) {
            return undefined;
        }

        const timeout = window.setTimeout(() => setCopiedExecutionId(''), 1800);
        return () => window.clearTimeout(timeout);
    }, [copiedExecutionId]);

    useEffect(() => {
        if (!copyError) {
            return undefined;
        }

        const timeout = window.setTimeout(() => setCopyError(''), 3000);
        return () => window.clearTimeout(timeout);
    }, [copyError]);

    useEffect(() => {
        return () => clearHoverHideTimeout();
    }, [clearHoverHideTimeout]);

    useEffect(() => {
        if (hidden) {
            setHoveredQuery(null);
        }
    }, [hidden]);

    useEffect(() => {
        setHoveredQuery(null);
    }, [entries, search]);

    return (
        <section className="panel history-panel" hidden={hidden} aria-label="Query history">
            <header className="inspection-heading">
                <div>
                    <h2>Query History</h2>
                    <p>Find, inspect and reuse previous queries</p>
                </div>
                <IconButton
                    icon="refresh"
                    title={loadingQueryHistory ? 'Refreshing history...' : 'Refresh history'}
                    onClick={onRefresh}
                    disabled={loadingQueryHistory}
                />
            </header>
            <div className="history-filters">
                <div className="filter-field">
                    <label htmlFor="history-datasource-filter">Connection</label>
                    <div className="select-wrap">
                        <select
                            id="history-datasource-filter"
                            value={datasourceFilter}
                            onChange={(event) => onDatasourceFilterChange(event.target.value)}
                        >
                            <option value="">All connections</option>
                            {visibleDatasources.map((datasource) => (
                                <option key={`history-${datasource.id}`} value={datasource.id}>
                                    {datasource.name}
                                </option>
                            ))}
                        </select>
                    </div>
                </div>

                <div className="filter-field">
                    <label htmlFor="history-status-filter">Status</label>
                    <div className="select-wrap">
                        <select
                            id="history-status-filter"
                            value={statusFilter}
                            onChange={(event) => onStatusFilterChange(event.target.value)}
                        >
                            <option value="">All statuses</option>
                            <option value="QUEUED">QUEUED</option>
                            <option value="RUNNING">RUNNING</option>
                            <option value="SUCCEEDED">SUCCEEDED</option>
                            <option value="FAILED">FAILED</option>
                            <option value="CANCELED">CANCELED</option>
                        </select>
                    </div>
                </div>

                <div className="filter-field">
                    <label htmlFor="history-from-filter">From</label>
                    <input
                        id="history-from-filter"
                        type="datetime-local"
                        value={fromFilter}
                        onChange={(event) => onFromFilterChange(event.target.value)}
                    />
                </div>

                <div className="filter-field">
                    <label htmlFor="history-to-filter">To</label>
                    <input
                        id="history-to-filter"
                        type="datetime-local"
                        value={toFilter}
                        onChange={(event) => onToFilterChange(event.target.value)}
                    />
                </div>
            </div>

            <div className="row toolbar-actions">
                <label className="inspection-search">
                    <IconGlyph icon="search" />
                    <input
                        aria-label="Search current history page"
                        placeholder="Search current page"
                        value={search}
                        onChange={(event) => setSearch(event.target.value)}
                    />
                </label>
                <span className="inspection-count">
                    {filteredEntries.length} of {entries.length} loaded queries
                </span>
                <LabeledActionButton
                    label="Export"
                    icon="download"
                    title={
                        filteredEntries.length > 0
                            ? 'Export visible history as CSV'
                            : 'No rows to export'
                    }
                    onClick={() => {
                        if (filteredEntries.length === 0) {
                            return;
                        }

                        const rows = filteredEntries.map((entry) => [
                            entry.submittedAt,
                            entry.status,
                            entry.datasourceId,
                            entry.justification ?? '',
                            typeof entry.durationMs === 'number' ? entry.durationMs.toString() : '',
                            entry.rowCount.toString(),
                            entry.queryTextRedacted ? '[REDACTED]' : (entry.queryText ?? '[empty]')
                        ]);

                        downloadCsv(
                            `query-history-page-${pageIndex + 1}.csv`,
                            toCsv(
                                [
                                    'Submitted',
                                    'Status',
                                    'Connection',
                                    'Justification',
                                    'DurationMs',
                                    'Rows',
                                    'Query'
                                ],
                                rows
                            )
                        );
                    }}
                    disabled={filteredEntries.length === 0}
                />
                <button type="button" className="chip" onClick={onToggleSortOrder}>
                    {sortOrder === 'newest' ? 'Newest first' : 'Oldest first'}
                </button>
                <button
                    type="button"
                    className="chip"
                    onClick={() => {
                        setSearch('');
                        onClearFilters();
                    }}
                >
                    Clear Filters
                </button>
            </div>

            {errorMessage ? <InlineNotice tone="error">{errorMessage}</InlineNotice> : null}
            {copyError ? <InlineNotice tone="error">{copyError}</InlineNotice> : null}
            {copiedExecutionId ? (
                <InlineNotice tone="success">Query text copied to clipboard.</InlineNotice>
            ) : null}

            <div className="history-table-wrap" onScroll={() => setHoveredQuery(null)}>
                <table className="result-table history-table query-history-table">
                    <thead>
                        <tr>
                            <th>Submitted</th>
                            <th>Status</th>
                            <th>Connection</th>
                            <th>Duration</th>
                            <th>Rows</th>
                            <th>Query</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {loadingQueryHistory && entries.length === 0 ? (
                            <tr>
                                <td colSpan={7}>Loading query history...</td>
                            </tr>
                        ) : filteredEntries.length === 0 ? (
                            <tr>
                                <td colSpan={7}>No history entries found for current filters.</td>
                            </tr>
                        ) : (
                            filteredEntries.map((entry) => {
                                const queryPreview = entry.queryTextRedacted
                                    ? '[REDACTED]'
                                    : (entry.queryText ?? '[empty]');
                                const justificationPreview = entry.justification?.trim() || '-';
                                const canUseQuery = Boolean(
                                    entry.queryText && !entry.queryTextRedacted
                                );

                                return (
                                    <Fragment key={`history-${entry.executionId}`}>
                                        <tr>
                                            <td>{new Date(entry.submittedAt).toLocaleString()}</td>
                                            <td>
                                                <span className={toStatusToneClass(entry.status)}>
                                                    {entry.status}
                                                </span>
                                            </td>
                                            <td>{entry.datasourceId}</td>
                                            <td>
                                                {typeof entry.durationMs === 'number'
                                                    ? `${entry.durationMs} ms`
                                                    : '-'}
                                            </td>
                                            <td>{entry.rowCount.toLocaleString()}</td>
                                            <td className="history-query-cell">
                                                <span
                                                    className="history-query-preview"
                                                    title={queryPreview}
                                                    tabIndex={canUseQuery ? 0 : undefined}
                                                    aria-describedby={
                                                        canUseQuery
                                                            ? `history-query-hover-${entry.executionId}`
                                                            : undefined
                                                    }
                                                    onMouseEnter={(
                                                        event: ReactMouseEvent<HTMLSpanElement>
                                                    ) => {
                                                        if (canUseQuery) {
                                                            showQueryHoverCard(
                                                                event.currentTarget,
                                                                entry.executionId,
                                                                queryPreview
                                                            );
                                                        }
                                                    }}
                                                    onMouseLeave={scheduleQueryHoverCardHide}
                                                    onFocus={(
                                                        event: ReactFocusEvent<HTMLSpanElement>
                                                    ) => {
                                                        if (canUseQuery) {
                                                            showQueryHoverCard(
                                                                event.currentTarget,
                                                                entry.executionId,
                                                                queryPreview
                                                            );
                                                        }
                                                    }}
                                                    onBlur={scheduleQueryHoverCardHide}
                                                >
                                                    {queryPreview}
                                                </span>
                                            </td>
                                            <td className="history-actions">
                                                <button
                                                    type="button"
                                                    className="chip"
                                                    aria-expanded={expandedId === entry.executionId}
                                                    onClick={() =>
                                                        setExpandedId(
                                                            expandedId === entry.executionId
                                                                ? ''
                                                                : entry.executionId
                                                        )
                                                    }
                                                    title="Inspect execution details"
                                                >
                                                    Details
                                                </button>
                                                <button
                                                    type="button"
                                                    className="chip"
                                                    disabled={
                                                        !entry.queryText || entry.queryTextRedacted
                                                    }
                                                    onClick={() => onOpenEntry(entry, false)}
                                                >
                                                    Open
                                                </button>
                                                <button
                                                    type="button"
                                                    disabled={
                                                        !entry.queryText || entry.queryTextRedacted
                                                    }
                                                    onClick={() => onOpenEntry(entry, true)}
                                                >
                                                    Rerun
                                                </button>
                                            </td>
                                        </tr>
                                        {expandedId === entry.executionId && (
                                            <tr className="inspection-detail-row">
                                                <td colSpan={7}>
                                                    <dl className="inspection-detail-fields">
                                                        <div>
                                                            <dt>Execution ID</dt>
                                                            <dd>{entry.executionId}</dd>
                                                        </div>
                                                        <div>
                                                            <dt>Actor</dt>
                                                            <dd>{entry.actor}</dd>
                                                        </div>
                                                        <div>
                                                            <dt>Credential profile</dt>
                                                            <dd>{entry.credentialProfile}</dd>
                                                        </div>
                                                        <div>
                                                            <dt>Justification</dt>
                                                            <dd>{justificationPreview}</dd>
                                                        </div>
                                                        <div>
                                                            <dt>Query limit</dt>
                                                            <dd>
                                                                {entry.maxRowsPerQuery.toLocaleString()}
                                                            </dd>
                                                        </div>
                                                        <div>
                                                            <dt>Runtime limit</dt>
                                                            <dd>{entry.maxRuntimeSeconds} s</dd>
                                                        </div>
                                                    </dl>
                                                    {entry.errorSummary && (
                                                        <InlineNotice tone="error">
                                                            {entry.errorSummary}
                                                        </InlineNotice>
                                                    )}
                                                    {entry.rowLimitReached && (
                                                        <InlineNotice tone="warning">
                                                            Query row limit reached. Results may be
                                                            partial.
                                                        </InlineNotice>
                                                    )}
                                                    <pre className="history-detail-sql">
                                                        {queryPreview}
                                                    </pre>
                                                </td>
                                            </tr>
                                        )}
                                    </Fragment>
                                );
                            })
                        )}
                    </tbody>
                </table>
            </div>
            <footer className="inspection-footer">
                <span>
                    {search
                        ? `${filteredEntries.length} matching queries on this page`
                        : entries.length
                          ? `Showing ${pageIndex * pageSize + 1}–${pageIndex * pageSize + entries.length}${hasNextPage ? ' · More available' : ''}`
                          : '0 queries'}
                </span>
                <div className="result-pagination-controls row">
                    <button
                        type="button"
                        onClick={() => onPageIndexChange(Math.max(0, pageIndex - 1))}
                        disabled={pageIndex <= 0}
                    >
                        Previous Page
                    </button>
                    <button
                        type="button"
                        onClick={() => onPageIndexChange(pageIndex + 1)}
                        disabled={!hasNextPage}
                    >
                        Next Page
                    </button>
                    <span className="muted-id">{`Page ${pageIndex + 1}`}</span>
                </div>
                <div className="result-page-size-inline">
                    <label htmlFor="history-page-size">Show rows</label>
                    <div className="select-wrap">
                        <select
                            id="history-page-size"
                            value={pageSize}
                            onChange={(event) => onPageSizeChange(Number(event.target.value))}
                        >
                            <option value={10}>10</option>
                            <option value={100}>100</option>
                            <option value={250}>250</option>
                            <option value={500}>500</option>
                            <option value={1000}>1000</option>
                        </select>
                    </div>
                </div>
            </footer>
            <HistoryQueryHoverCard
                copiedExecutionId={copiedExecutionId}
                hoveredQuery={hoveredQuery}
                onCopy={handleCopyQuery}
                onMouseEnter={clearHoverHideTimeout}
                onMouseLeave={scheduleQueryHoverCardHide}
            />
        </section>
    );
}
