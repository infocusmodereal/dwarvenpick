import type { AuditEventResponse } from '../types';
import { IconButton } from '../components/WorkbenchIcons';
import { toStatusToneClass } from '../utils';

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
                <button type="button" className="chip" onClick={onToggleSortOrder}>
                    {auditSortOrder === 'newest' ? 'Newest first' : 'Oldest first'}
                </button>
                <button type="button" className="chip" onClick={onClearFilters}>
                    Clear Filters
                </button>
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
                            events.map((event, index) => (
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
