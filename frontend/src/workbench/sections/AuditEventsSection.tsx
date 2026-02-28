import type { AuditEventResponse } from '../types';
import { IconButton } from '../components/WorkbenchIcons';
import { toStatusToneClass } from '../utils';
import { useEffect, useMemo, useState } from 'react';

type AuditEventsSectionProps = {
    hidden: boolean;
    auditActionOptions: string[];
    auditTypeFilter: string;
    onAuditTypeFilterChange: (value: string) => void;
    auditActorFilter: string;
    onAuditActorFilterChange: (value: string) => void;
    auditOutcomeOptions: string[];
    auditOutcomeFilter: string;
    onAuditOutcomeFilterChange: (value: string) => void;
    auditFromFilter: string;
    onAuditFromFilterChange: (value: string) => void;
    auditToFilter: string;
    onAuditToFilterChange: (value: string) => void;
    loadingAuditEvents: boolean;
    onRefresh: () => void;
    auditSortOrder: 'newest' | 'oldest';
    onToggleSortOrder: () => void;
    onClearFilters: () => void;
    events: AuditEventResponse[];
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

export default function AuditEventsSection({
    hidden,
    auditActionOptions,
    auditTypeFilter,
    onAuditTypeFilterChange,
    auditActorFilter,
    onAuditActorFilterChange,
    auditOutcomeOptions,
    auditOutcomeFilter,
    onAuditOutcomeFilterChange,
    auditFromFilter,
    onAuditFromFilterChange,
    auditToFilter,
    onAuditToFilterChange,
    loadingAuditEvents,
    onRefresh,
    auditSortOrder,
    onToggleSortOrder,
    onClearFilters,
    events
}: AuditEventsSectionProps) {
    const [pageSize, setPageSize] = useState(100);
    const [pageIndex, setPageIndex] = useState(0);

    useEffect(() => {
        setPageIndex(0);
    }, [
        auditActorFilter,
        auditFromFilter,
        auditOutcomeFilter,
        auditToFilter,
        auditTypeFilter,
        auditSortOrder,
        pageSize
    ]);

    const totalPages = Math.max(1, Math.ceil(events.length / pageSize));
    const resolvedPageIndex = Math.min(pageIndex, totalPages - 1);
    const pagedEvents = useMemo(() => {
        const start = resolvedPageIndex * pageSize;
        return events.slice(start, start + pageSize);
    }, [events, pageSize, resolvedPageIndex]);

    return (
        <section className="panel admin-audit" hidden={hidden}>
            <div className="history-filters">
                <div className="filter-field">
                    <label htmlFor="audit-type-filter">Action</label>
                    <div className="select-wrap">
                        <select
                            id="audit-type-filter"
                            value={auditTypeFilter}
                            onChange={(event) => onAuditTypeFilterChange(event.target.value)}
                        >
                            <option value="">All actions</option>
                            {auditActionOptions.map((action) => (
                                <option key={`audit-action-${action}`} value={action}>
                                    {action}
                                </option>
                            ))}
                        </select>
                    </div>
                </div>

                <div className="filter-field">
                    <label htmlFor="audit-actor-filter">Actor</label>
                    <input
                        id="audit-actor-filter"
                        value={auditActorFilter}
                        onChange={(event) => onAuditActorFilterChange(event.target.value)}
                        placeholder="admin or /^adm/i"
                    />
                </div>

                <div className="filter-field">
                    <label htmlFor="audit-outcome-filter">Outcome</label>
                    <div className="select-wrap">
                        <select
                            id="audit-outcome-filter"
                            value={auditOutcomeFilter}
                            onChange={(event) => onAuditOutcomeFilterChange(event.target.value)}
                        >
                            <option value="">All outcomes</option>
                            {auditOutcomeOptions.map((outcome) => (
                                <option key={`audit-outcome-${outcome}`} value={outcome}>
                                    {outcome}
                                </option>
                            ))}
                        </select>
                    </div>
                </div>

                <div className="filter-field">
                    <label htmlFor="audit-from-filter">From</label>
                    <input
                        id="audit-from-filter"
                        type="datetime-local"
                        value={auditFromFilter}
                        onChange={(event) => onAuditFromFilterChange(event.target.value)}
                    />
                </div>

                <div className="filter-field">
                    <label htmlFor="audit-to-filter">To</label>
                    <input
                        id="audit-to-filter"
                        type="datetime-local"
                        value={auditToFilter}
                        onChange={(event) => onAuditToFilterChange(event.target.value)}
                    />
                </div>
            </div>

            <div className="row toolbar-actions">
                <IconButton
                    icon="refresh"
                    title={
                        loadingAuditEvents ? 'Refreshing audit events...' : 'Refresh audit events'
                    }
                    onClick={onRefresh}
                    disabled={loadingAuditEvents}
                />
                <IconButton
                    icon="download"
                    title={pagedEvents.length > 0 ? 'Export CSV' : 'No rows to export'}
                    onClick={() => {
                        if (pagedEvents.length === 0) {
                            return;
                        }

                        const rows = pagedEvents.map((event) => [
                            event.timestamp,
                            event.type,
                            event.actor ?? 'anonymous',
                            event.outcome,
                            JSON.stringify(event.details)
                        ]);

                        downloadCsv(
                            `audit-events-page-${resolvedPageIndex + 1}.csv`,
                            toCsv(['Timestamp', 'Action', 'Actor', 'Outcome', 'Details'], rows)
                        );
                    }}
                    disabled={pagedEvents.length === 0}
                />
                <button type="button" className="chip" onClick={onToggleSortOrder}>
                    {auditSortOrder === 'newest' ? 'Newest first' : 'Oldest first'}
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
                    <label htmlFor="audit-page-size">Rows per page</label>
                    <div className="select-wrap">
                        <select
                            id="audit-page-size"
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
                            <th>Timestamp</th>
                            <th>Action</th>
                            <th>Actor</th>
                            <th>Outcome</th>
                            <th>Details</th>
                        </tr>
                    </thead>
                    <tbody>
                        {events.length === 0 ? (
                            <tr>
                                <td colSpan={5}>No audit events found for current filters.</td>
                            </tr>
                        ) : (
                            pagedEvents.map((event, index) => (
                                <tr key={`audit-${event.timestamp}-${index}`}>
                                    <td>{new Date(event.timestamp).toLocaleString()}</td>
                                    <td>{event.type}</td>
                                    <td>{event.actor ?? 'anonymous'}</td>
                                    <td>
                                        <span className={toStatusToneClass(event.outcome)}>
                                            {event.outcome}
                                        </span>
                                    </td>
                                    <td className="history-query audit-details">
                                        {JSON.stringify(event.details)}
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
