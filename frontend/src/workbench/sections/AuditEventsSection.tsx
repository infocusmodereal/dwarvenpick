import CompactPagination from '../components/CompactPagination';
import type { AuditEventResponse } from '../types';
import { IconButton, IconGlyph, LabeledActionButton } from '../components/WorkbenchIcons';
import InlineNotice from '../components/InlineNotice';
import { toStatusToneClass } from '../utils';
import { Fragment, useEffect, useMemo, useState } from 'react';

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
    errorMessage: string;
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
    errorMessage,
    onRefresh,
    auditSortOrder,
    onToggleSortOrder,
    onClearFilters,
    events
}: AuditEventsSectionProps) {
    const [pageSize, setPageSize] = useState(100);
    const [search, setSearch] = useState('');
    const [expandedEvent, setExpandedEvent] = useState<AuditEventResponse | null>(null);
    const matchingEvents = useMemo(() => {
        const term = search.trim().toLowerCase();
        return term
            ? events.filter((event) =>
                  [event.type, event.actor, event.outcome, JSON.stringify(event.details)]
                      .join(' ')
                      .toLowerCase()
                      .includes(term)
              )
            : events;
    }, [events, search]);
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
        pageSize,
        search
    ]);

    const totalPages = Math.max(1, Math.ceil(matchingEvents.length / pageSize));
    const resolvedPageIndex = Math.min(pageIndex, totalPages - 1);
    const pagedEvents = useMemo(() => {
        const start = resolvedPageIndex * pageSize;
        return matchingEvents.slice(start, start + pageSize);
    }, [matchingEvents, pageSize, resolvedPageIndex]);

    return (
        <section className="panel admin-audit" hidden={hidden} aria-label="Audit events">
            <header className="audit-heading">
                <div>
                    <h2>Audit Events</h2>
                    <p>Activity, access and configuration changes</p>
                </div>
                <div className="audit-heading-actions">
                    <IconButton
                        icon="refresh"
                        title={
                            loadingAuditEvents
                                ? 'Refreshing audit events...'
                                : 'Refresh audit events'
                        }
                        onClick={onRefresh}
                        disabled={loadingAuditEvents}
                    />
                </div>
            </header>
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
                        placeholder="Filter by actor"
                        title="Actor name or regular expression, for example /^adm/i"
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
                <label className="audit-search">
                    <IconGlyph icon="search" />
                    <input
                        aria-label="Search loaded events"
                        placeholder="Search loaded events"
                        value={search}
                        onChange={(event) => setSearch(event.target.value)}
                    />
                </label>
                <span className="audit-loaded-count" role="status">
                    {matchingEvents.length} of {events.length} loaded events
                    {events.length >= 200 ? ' · Latest 200' : ''}
                </span>
                <LabeledActionButton
                    label="Export"
                    icon="download"
                    title={
                        pagedEvents.length > 0 ? 'Export current page as CSV' : 'No rows to export'
                    }
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
                        {loadingAuditEvents && events.length === 0 ? (
                            <tr>
                                <td colSpan={5}>Loading audit events...</td>
                            </tr>
                        ) : matchingEvents.length === 0 ? (
                            <tr>
                                <td colSpan={5}>No audit events found for current filters.</td>
                            </tr>
                        ) : (
                            pagedEvents.map((event, index) => (
                                <Fragment key={`audit-${event.timestamp}-${event.type}-${index}`}>
                                    <tr
                                        className={
                                            expandedEvent === event ? 'audit-row-selected' : ''
                                        }
                                    >
                                        <td>{new Date(event.timestamp).toLocaleString()}</td>
                                        <td>{event.type}</td>
                                        <td>{event.actor ?? 'anonymous'}</td>
                                        <td>
                                            <span className={toStatusToneClass(event.outcome)}>
                                                {event.outcome}
                                            </span>
                                        </td>
                                        <td className="audit-details">
                                            <div className="audit-detail-summary">
                                                <span className="audit-detail-preview">
                                                    {Object.entries(event.details)
                                                        .slice(0, 2)
                                                        .map(
                                                            ([key, value]) =>
                                                                `${key}: ${typeof value === 'object' ? JSON.stringify(value) : String(value)}`
                                                        )
                                                        .join(' · ') || 'No additional details'}
                                                </span>
                                                <button
                                                    type="button"
                                                    className="audit-inspect"
                                                    aria-expanded={expandedEvent === event}
                                                    aria-controls={`audit-detail-${index}`}
                                                    onClick={() =>
                                                        setExpandedEvent(
                                                            expandedEvent === event ? null : event
                                                        )
                                                    }
                                                >
                                                    {expandedEvent === event
                                                        ? 'Hide details'
                                                        : 'View details'}
                                                    <span>
                                                        {Object.keys(event.details).length} fields
                                                    </span>
                                                </button>
                                            </div>
                                        </td>
                                    </tr>
                                    {expandedEvent === event && (
                                        <tr className="audit-expanded-row">
                                            <td colSpan={5}>
                                                <dl
                                                    id={`audit-detail-${index}`}
                                                    className="audit-detail-fields"
                                                >
                                                    {Object.entries(event.details).map(
                                                        ([key, value]) => (
                                                            <div key={key}>
                                                                <dt>{key}</dt>
                                                                <dd>
                                                                    {typeof value === 'object'
                                                                        ? JSON.stringify(
                                                                              value,
                                                                              null,
                                                                              2
                                                                          )
                                                                        : String(value)}
                                                                </dd>
                                                            </div>
                                                        )
                                                    )}
                                                </dl>
                                                {Object.keys(event.details).length === 0 && (
                                                    <p>No additional details.</p>
                                                )}
                                            </td>
                                        </tr>
                                    )}
                                </Fragment>
                            ))
                        )}
                    </tbody>
                </table>
            </div>
            <footer className="audit-footer">
                <span>
                    {matchingEvents.length
                        ? `Showing ${resolvedPageIndex * pageSize + 1}–${Math.min((resolvedPageIndex + 1) * pageSize, matchingEvents.length)} of ${matchingEvents.length}`
                        : '0 events'}
                </span>
                <CompactPagination
                    label="Audit event pages"
                    pageIndex={resolvedPageIndex}
                    totalPages={totalPages}
                    hasNextPage={resolvedPageIndex < totalPages - 1}
                    disabled={loadingAuditEvents}
                    onPageChange={setPageIndex}
                />
                <div className="result-page-size-inline">
                    <label htmlFor="audit-page-size">Show rows</label>
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
            </footer>
        </section>
    );
}
