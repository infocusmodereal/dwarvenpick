import type { CatalogDatasourceResponse, QueryHistoryEntryResponse } from '../types';
import { toStatusToneClass } from '../utils';
import { IconButton } from '../components/WorkbenchIcons';
import { useCallback, useEffect, useRef, useState } from 'react';
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

const copyToClipboard = async (value: string) => {
    if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(value);
        return;
    }

    const textArea = document.createElement('textarea');
    textArea.value = value;
    textArea.setAttribute('readonly', 'true');
    textArea.style.position = 'fixed';
    textArea.style.left = '-9999px';
    document.body.appendChild(textArea);
    textArea.select();
    document.execCommand('copy');
    textArea.remove();
};

type QueryHoverCard = {
    executionId: string;
    queryText: string;
    top: number;
    left: number;
    width: number;
};

const HOVER_CARD_MAX_WIDTH = 672;
const HOVER_CARD_MIN_WIDTH = 320;
const HOVER_CARD_MAX_HEIGHT = 224;
const HOVER_CARD_MARGIN = 16;

const clamp = (value: number, min: number, max: number) => {
    return Math.min(Math.max(value, min), max);
};

const getQueryHoverCard = (
    trigger: HTMLElement,
    executionId: string,
    queryText: string
): QueryHoverCard => {
    const rect = trigger.getBoundingClientRect();
    const availableWidth = Math.max(
        HOVER_CARD_MIN_WIDTH,
        window.innerWidth - HOVER_CARD_MARGIN * 2
    );
    const width = Math.min(HOVER_CARD_MAX_WIDTH, availableWidth);
    const left = clamp(
        rect.right - width,
        HOVER_CARD_MARGIN,
        Math.max(HOVER_CARD_MARGIN, window.innerWidth - width - HOVER_CARD_MARGIN)
    );
    const preferredTop = rect.bottom + 6;
    const top =
        preferredTop + HOVER_CARD_MAX_HEIGHT > window.innerHeight
            ? Math.max(HOVER_CARD_MARGIN, rect.top - HOVER_CARD_MAX_HEIGHT - 6)
            : preferredTop;

    return {
        executionId,
        queryText,
        top,
        left,
        width
    };
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
    const [copiedExecutionId, setCopiedExecutionId] = useState('');
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
        await copyToClipboard(queryText);
        setCopiedExecutionId(executionId);
    }, []);

    useEffect(() => {
        if (!copiedExecutionId) {
            return undefined;
        }

        const timeout = window.setTimeout(() => setCopiedExecutionId(''), 1800);
        return () => window.clearTimeout(timeout);
    }, [copiedExecutionId]);

    useEffect(() => {
        return () => clearHoverHideTimeout();
    }, [clearHoverHideTimeout]);

    useEffect(() => {
        if (hidden) {
            setHoveredQuery(null);
        }
    }, [hidden]);

    return (
        <section className="panel history-panel" hidden={hidden}>
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
                <IconButton
                    icon="refresh"
                    title={loadingQueryHistory ? 'Refreshing history...' : 'Refresh history'}
                    onClick={onRefresh}
                    disabled={loadingQueryHistory}
                />
                <IconButton
                    icon="download"
                    title={entries.length > 0 ? 'Export CSV' : 'No rows to export'}
                    onClick={() => {
                        if (entries.length === 0) {
                            return;
                        }

                        const rows = entries.map((entry) => [
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
                    disabled={entries.length === 0}
                />
                <button type="button" className="chip" onClick={onToggleSortOrder}>
                    {sortOrder === 'newest' ? 'Newest first' : 'Oldest first'}
                </button>
                <button type="button" className="chip" onClick={onClearFilters}>
                    Clear Filters
                </button>
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
                    <label htmlFor="history-page-size">Rows per page</label>
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
            </div>

            <div className="history-table-wrap" onScroll={() => setHoveredQuery(null)}>
                <table className="result-table history-table query-history-table">
                    <thead>
                        <tr>
                            <th>Submitted</th>
                            <th>Status</th>
                            <th>Connection</th>
                            <th>Justification</th>
                            <th>Duration</th>
                            <th>Rows</th>
                            <th>Query</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {entries.length === 0 ? (
                            <tr>
                                <td colSpan={8}>No history entries found for current filters.</td>
                            </tr>
                        ) : (
                            entries.map((entry) => {
                                const queryPreview = entry.queryTextRedacted
                                    ? '[REDACTED]'
                                    : (entry.queryText ?? '[empty]');
                                const justificationPreview = entry.justification?.trim() || '-';
                                const canUseQuery = Boolean(
                                    entry.queryText && !entry.queryTextRedacted
                                );

                                return (
                                    <tr key={`history-${entry.executionId}`}>
                                        <td>{new Date(entry.submittedAt).toLocaleString()}</td>
                                        <td>
                                            <span className={toStatusToneClass(entry.status)}>
                                                {entry.status}
                                            </span>
                                        </td>
                                        <td>{entry.datasourceId}</td>
                                        <td
                                            className="history-justification-cell"
                                            title={justificationPreview}
                                        >
                                            {justificationPreview}
                                        </td>
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
                                );
                            })
                        )}
                    </tbody>
                </table>
            </div>
            {hoveredQuery ? (
                <div
                    id={`history-query-hover-${hoveredQuery.executionId}`}
                    className="history-query-hover-card"
                    role="tooltip"
                    style={{
                        top: hoveredQuery.top,
                        left: hoveredQuery.left,
                        width: hoveredQuery.width
                    }}
                    onMouseEnter={clearHoverHideTimeout}
                    onMouseLeave={scheduleQueryHoverCardHide}
                >
                    <code className="history-query-full">{hoveredQuery.queryText}</code>
                    <button
                        type="button"
                        className="history-query-copy"
                        onClick={(event) => {
                            event.preventDefault();
                            event.stopPropagation();
                            void handleCopyQuery(hoveredQuery.executionId, hoveredQuery.queryText);
                        }}
                    >
                        {copiedExecutionId === hoveredQuery.executionId ? 'Copied' : 'Copy'}
                    </button>
                </div>
            ) : null}
        </section>
    );
}
