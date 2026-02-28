import type { CatalogDatasourceResponse, QueryHistoryEntryResponse } from '../types';
import { toStatusToneClass } from '../utils';
import { IconButton } from '../components/WorkbenchIcons';
import { useEffect, useMemo, useState } from 'react';

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
    onRefresh,
    sortOrder,
    onToggleSortOrder,
    onClearFilters,
    entries,
    onOpenEntry
}: QueryHistorySectionProps) {
    const [pageSize, setPageSize] = useState(100);
    const [pageIndex, setPageIndex] = useState(0);

    useEffect(() => {
        setPageIndex(0);
    }, [datasourceFilter, fromFilter, pageSize, sortOrder, statusFilter, toFilter]);

    const totalPages = Math.max(1, Math.ceil(entries.length / pageSize));
    const resolvedPageIndex = Math.min(pageIndex, totalPages - 1);
    const pagedEntries = useMemo(() => {
        const start = resolvedPageIndex * pageSize;
        return entries.slice(start, start + pageSize);
    }, [entries, pageSize, resolvedPageIndex]);

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
                    title={pagedEntries.length > 0 ? 'Export CSV' : 'No rows to export'}
                    onClick={() => {
                        if (pagedEntries.length === 0) {
                            return;
                        }

                        const rows = pagedEntries.map((entry) => [
                            entry.submittedAt,
                            entry.status,
                            entry.datasourceId,
                            typeof entry.durationMs === 'number' ? entry.durationMs.toString() : '',
                            entry.rowCount.toString(),
                            entry.queryTextRedacted ? '[REDACTED]' : (entry.queryText ?? '[empty]')
                        ]);

                        downloadCsv(
                            `query-history-page-${resolvedPageIndex + 1}.csv`,
                            toCsv(
                                [
                                    'Submitted',
                                    'Status',
                                    'Connection',
                                    'DurationMs',
                                    'Rows',
                                    'Query'
                                ],
                                rows
                            )
                        );
                    }}
                    disabled={pagedEntries.length === 0}
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
                        onClick={() => setPageIndex((current) => Math.max(0, current - 1))}
                        disabled={resolvedPageIndex <= 0}
                    >
                        Previous Page
                    </button>
                    <button
                        type="button"
                        onClick={() =>
                            setPageIndex((current) => Math.min(totalPages - 1, current + 1))
                        }
                        disabled={resolvedPageIndex >= totalPages - 1}
                    >
                        Next Page
                    </button>
                    <span className="muted-id">{`Page ${resolvedPageIndex + 1} of ${totalPages}`}</span>
                </div>
                <div className="result-page-size-inline">
                    <label htmlFor="history-page-size">Rows per page</label>
                    <div className="select-wrap">
                        <select
                            id="history-page-size"
                            value={pageSize}
                            onChange={(event) => setPageSize(Number(event.target.value))}
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

            <div className="history-table-wrap">
                <table className="result-table history-table">
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
                        {entries.length === 0 ? (
                            <tr>
                                <td colSpan={7}>No history entries found for current filters.</td>
                            </tr>
                        ) : (
                            pagedEntries.map((entry) => (
                                <tr key={`history-${entry.executionId}`}>
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
                                    <td className="history-query">
                                        {entry.queryTextRedacted
                                            ? '[REDACTED]'
                                            : (entry.queryText ?? '[empty]')}
                                    </td>
                                    <td className="history-actions">
                                        <button
                                            type="button"
                                            className="chip"
                                            disabled={!entry.queryText || entry.queryTextRedacted}
                                            onClick={() => onOpenEntry(entry, false)}
                                        >
                                            Open
                                        </button>
                                        <button
                                            type="button"
                                            disabled={!entry.queryText || entry.queryTextRedacted}
                                            onClick={() => onOpenEntry(entry, true)}
                                        >
                                            Rerun
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
