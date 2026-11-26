import { useEffect, useRef, useState } from 'react';
import CompactPagination from '../components/CompactPagination';
import HistoryDisclosure from '../components/HistoryDisclosure';
import InlineNotice from '../components/InlineNotice';
import QueryHistoryDetails, { QueryHistoryStatus } from '../components/QueryHistoryDetails';
import SearchablePicker from '../components/SearchablePicker';
import { IconButton, IconGlyph, LabeledActionButton } from '../components/WorkbenchIcons';
import { downloadCsv, toCsv } from '../csvDownload';
import { historyQueryText } from '../historyQueryText';
import { sortDownIcon, sortUpIcon } from '../icons';
import type { CatalogDatasourceResponse, QueryHistoryEntryResponse } from '../types';

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
    totalCount?: number | null;
    onPageIndexChange: (value: number) => void;
    onPageSizeChange: (value: number) => void;
    onOpenEntry: (entry: QueryHistoryEntryResponse, rerun: boolean) => void;
};

const statusOptions = [
    { value: '', label: 'All statuses' },
    { value: 'QUEUED', label: 'Queued' },
    { value: 'RUNNING', label: 'Running' },
    { value: 'SUCCEEDED', label: 'Succeeded' },
    { value: 'FAILED', label: 'Failed' },
    { value: 'CANCELED', label: 'Canceled' }
];
const pageSizeOptions = [10, 100, 250, 500, 1000].map((size) => ({
    value: String(size),
    label: String(size)
}));

const submittedFormat = new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit'
});
const statementKeyword =
    /^(SELECT|WITH|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|SHOW|EXPLAIN|DESCRIBE|SET|USE|CALL)\b/i;

function formatSubmitted(value: string): string {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : submittedFormat.format(date);
}

function exportHistory(entries: QueryHistoryEntryResponse[], pageIndex: number) {
    const rows = entries.map((entry) => [
        entry.submittedAt,
        entry.status,
        entry.datasourceId,
        entry.actor,
        entry.justification ?? '',
        typeof entry.durationMs === 'number' ? entry.durationMs.toString() : '',
        entry.rowCount.toString(),
        historyQueryText(entry)
    ]);
    downloadCsv(
        `query-history-page-${pageIndex + 1}.csv`,
        toCsv(
            [
                'Submitted',
                'Status',
                'Connection',
                'User',
                'Justification',
                'DurationMs',
                'Rows',
                'Query'
            ],
            rows
        )
    );
}

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
    totalCount = null,
    onPageIndexChange,
    onPageSizeChange,
    onOpenEntry
}: QueryHistorySectionProps) {
    const [search, setSearch] = useState('');
    const [selectedId, setSelectedId] = useState('');
    const selectedTrigger = useRef<HTMLButtonElement | null>(null);
    const term = search.trim().toLowerCase();
    const filteredEntries = entries.filter((entry) =>
        [
            entry.datasourceId,
            entry.actor,
            entry.status,
            entry.justification,
            entry.queryTextRedacted ? '' : entry.queryText
        ]
            .join(' ')
            .toLowerCase()
            .includes(term)
    );
    const selected = filteredEntries.find((entry) => entry.executionId === selectedId);
    const dateFiltered = Boolean(fromFilter || toFilter);
    const hasFilters = Boolean(term || datasourceFilter || statusFilter || dateFiltered);

    useEffect(() => {
        setSearch('');
        setSelectedId('');
    }, [pageIndex, pageSize, datasourceFilter, statusFilter, fromFilter, toFilter]);

    const closeDetails = () => {
        setSelectedId('');
        selectedTrigger.current?.focus();
    };

    return (
        <section className="panel history-panel" hidden={hidden} aria-label="Query history">
            <header className="inspection-heading">
                <h2>Query History</h2>
            </header>
            <div className="query-history-toolbar">
                <label className="inspection-search">
                    <IconGlyph icon="search" />
                    <input
                        aria-label="Search current history page"
                        placeholder="Search this page"
                        value={search}
                        onChange={(event) => {
                            setSearch(event.target.value);
                            setSelectedId('');
                        }}
                    />
                </label>
                <HistoryDisclosure
                    label="Date range"
                    trigger={
                        <>
                            {dateFiltered ? 'Date range set' : 'All dates'}{' '}
                            <IconGlyph icon="chevron-down" />
                        </>
                    }
                >
                    <label>
                        From{' '}
                        <input
                            type="datetime-local"
                            value={fromFilter}
                            onChange={(event) => onFromFilterChange(event.target.value)}
                        />
                    </label>
                    <label>
                        To{' '}
                        <input
                            type="datetime-local"
                            value={toFilter}
                            onChange={(event) => onToFilterChange(event.target.value)}
                        />
                    </label>
                </HistoryDisclosure>
                <SearchablePicker
                    noun="connection"
                    triggerLabel="Connection"
                    value={datasourceFilter}
                    onChange={onDatasourceFilterChange}
                    searchable={visibleDatasources.length > 8}
                    items={[
                        { value: '', label: 'All connections' },
                        ...visibleDatasources.map(({ id, name }) => ({ value: id, label: name }))
                    ]}
                />
                <SearchablePicker
                    noun="status"
                    triggerLabel="Status"
                    value={statusFilter}
                    onChange={onStatusFilterChange}
                    searchable={false}
                    items={statusOptions}
                />
                {hasFilters && (
                    <button
                        type="button"
                        className="history-clear"
                        onClick={() => {
                            setSearch('');
                            setSelectedId('');
                            onClearFilters();
                        }}
                    >
                        Clear filters
                    </button>
                )}
                <div className="history-toolbar-actions">
                    <IconButton
                        icon="refresh"
                        title={loadingQueryHistory ? 'Refreshing history...' : 'Refresh history'}
                        onClick={onRefresh}
                        disabled={loadingQueryHistory}
                    />
                    <LabeledActionButton
                        icon="download"
                        label="Export CSV"
                        title="Export visible history as CSV"
                        onClick={() => exportHistory(filteredEntries, pageIndex)}
                        disabled={filteredEntries.length === 0}
                    />
                </div>
            </div>
            {errorMessage && <InlineNotice tone="error">{errorMessage}</InlineNotice>}
            <div className={`query-history-split${selected ? ' has-selection' : ''}`}>
                <div className="history-table-wrap">
                    <table className="result-table history-table query-history-table">
                        <thead>
                            <tr>
                                <th scope="col" className="history-query-column">
                                    Query
                                </th>
                                <th scope="col" className="history-connection-column">
                                    Connection
                                </th>
                                <th scope="col" className="history-user-column">
                                    User
                                </th>
                                <th scope="col" className="history-status-column">
                                    Status
                                </th>
                                <th
                                    scope="col"
                                    className="history-submitted-column"
                                    aria-sort={sortOrder === 'newest' ? 'descending' : 'ascending'}
                                >
                                    <button
                                        type="button"
                                        className="history-sort"
                                        onClick={onToggleSortOrder}
                                        title={
                                            sortOrder === 'newest'
                                                ? 'Show oldest first'
                                                : 'Show newest first'
                                        }
                                    >
                                        Submitted
                                        <span
                                            aria-hidden
                                            dangerouslySetInnerHTML={{
                                                __html:
                                                    sortOrder === 'newest'
                                                        ? sortDownIcon
                                                        : sortUpIcon
                                            }}
                                        />
                                    </button>
                                </th>
                            </tr>
                        </thead>
                        <tbody>
                            {filteredEntries.map((entry) => {
                                const current = selectedId === entry.executionId;
                                const text = historyQueryText(entry).trimStart();
                                const preview = text.slice(0, 320).replace(/\s+/g, ' ').trimEnd();
                                const sql = text.length > 320 ? `${preview}…` : preview;
                                const keyword = sql.match(statementKeyword)?.[0] ?? '';
                                const select = (trigger: HTMLButtonElement | null) => {
                                    if (!trigger) return;
                                    selectedTrigger.current = trigger;
                                    setSelectedId(entry.executionId);
                                };
                                return (
                                    <tr
                                        key={entry.executionId}
                                        className={
                                            current
                                                ? 'query-history-row is-selected'
                                                : 'query-history-row'
                                        }
                                        onClick={(event) =>
                                            select(event.currentTarget.querySelector('button'))
                                        }
                                    >
                                        <td>
                                            <button
                                                type="button"
                                                className="history-query-preview"
                                                aria-label={`Inspect query: ${sql}`}
                                                aria-expanded={current}
                                                aria-controls={
                                                    current ? 'query-history-details' : undefined
                                                }
                                                onClick={(event) => {
                                                    event.stopPropagation();
                                                    select(event.currentTarget);
                                                }}
                                            >
                                                {keyword && <span>{keyword}</span>}
                                                {sql.slice(keyword.length)}
                                            </button>
                                        </td>
                                        <td>
                                            <span
                                                className="history-cell-text"
                                                title={entry.datasourceId}
                                            >
                                                {entry.datasourceId}
                                            </span>
                                        </td>
                                        <td>
                                            <span className="history-cell-text" title={entry.actor}>
                                                {entry.actor}
                                            </span>
                                        </td>
                                        <td>
                                            <QueryHistoryStatus status={entry.status} />
                                        </td>
                                        <td>
                                            <time
                                                dateTime={entry.submittedAt}
                                                title={new Date(entry.submittedAt).toLocaleString()}
                                            >
                                                {formatSubmitted(entry.submittedAt)}
                                            </time>
                                        </td>
                                    </tr>
                                );
                            })}
                            {filteredEntries.length === 0 && (
                                <tr>
                                    <td colSpan={5}>
                                        {loadingQueryHistory && entries.length === 0
                                            ? 'Loading query history...'
                                            : 'No history entries found for current filters.'}
                                    </td>
                                </tr>
                            )}
                        </tbody>
                    </table>
                </div>
                {selected && (
                    <QueryHistoryDetails
                        key={selected.executionId}
                        entry={selected}
                        onClose={closeDetails}
                        onOpenEntry={onOpenEntry}
                    />
                )}
            </div>
            <footer className="inspection-footer query-history-footer">
                <span>
                    {term
                        ? `${filteredEntries.length} of ${entries.length} loaded queries`
                        : entries.length
                          ? `Showing ${pageIndex * pageSize + 1}–${pageIndex * pageSize + entries.length}${totalCount !== null ? ` of ${totalCount}` : hasNextPage ? ' · More available' : ''}`
                          : '0 queries'}
                </span>
                {(pageIndex > 0 || hasNextPage) && (
                    <CompactPagination
                        label="Query history pages"
                        pageIndex={pageIndex}
                        totalPages={
                            totalCount !== null
                                ? Math.max(1, Math.ceil(totalCount / pageSize))
                                : !hasNextPage
                                  ? pageIndex + 1
                                  : null
                        }
                        hasNextPage={hasNextPage}
                        disabled={loadingQueryHistory}
                        onPageChange={onPageIndexChange}
                    />
                )}
                {(pageIndex > 0 || hasNextPage || entries.length > 10) && (
                    <div className="result-page-size-inline">
                        <span className="history-page-size-label" aria-hidden>
                            Show rows
                        </span>
                        <SearchablePicker
                            noun="page size"
                            triggerLabel="Show rows"
                            placement="top"
                            value={String(pageSize)}
                            onChange={(size) => onPageSizeChange(Number(size))}
                            searchable={false}
                            items={pageSizeOptions}
                        />
                    </div>
                )}
            </footer>
        </section>
    );
}
