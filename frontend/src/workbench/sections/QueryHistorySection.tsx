import type { CatalogDatasourceResponse, QueryHistoryEntryResponse } from '../types';
import { toStatusToneClass } from '../utils';
import { IconButton } from '../components/WorkbenchIcons';

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
                <button type="button" className="chip" onClick={onToggleSortOrder}>
                    {sortOrder === 'newest' ? 'Newest first' : 'Oldest first'}
                </button>
                <button type="button" className="chip" onClick={onClearFilters}>
                    Clear Filters
                </button>
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
                            entries.map((entry) => (
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
