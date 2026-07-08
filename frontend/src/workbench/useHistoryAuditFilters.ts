import { useMemo, useState } from 'react';
import { builtInAuditActions, builtInAuditOutcomes } from './constants';
import type { AuditEventResponse, QueryHistoryEntryResponse } from './types';

export type SortOrder = 'newest' | 'oldest';

const timestampValue = (value: string): number => {
    const timestamp = new Date(value).getTime();
    return Number.isFinite(timestamp) ? timestamp : 0;
};

export const sortQueryHistoryEntries = (
    entries: QueryHistoryEntryResponse[],
    sortOrder: SortOrder
): QueryHistoryEntryResponse[] => {
    const rows = [...entries];
    rows.sort((left, right) => {
        const diff = timestampValue(left.submittedAt) - timestampValue(right.submittedAt);
        return sortOrder === 'newest' ? -diff : diff;
    });
    return rows;
};

export const sortAuditEvents = (
    events: AuditEventResponse[],
    sortOrder: SortOrder
): AuditEventResponse[] => {
    const rows = [...events];
    rows.sort((left, right) => {
        const diff = timestampValue(left.timestamp) - timestampValue(right.timestamp);
        return sortOrder === 'newest' ? -diff : diff;
    });
    return rows;
};

export const buildAuditActionOptions = (events: AuditEventResponse[]): string[] => {
    const options = new Set<string>(builtInAuditActions);
    events.forEach((event) => {
        if (event.type.trim()) {
            options.add(event.type.trim());
        }
    });
    return Array.from(options).sort((left, right) => left.localeCompare(right));
};

export const buildAuditOutcomeOptions = (events: AuditEventResponse[]): string[] => {
    const options = new Set<string>(builtInAuditOutcomes);
    events.forEach((event) => {
        if (event.outcome.trim()) {
            options.add(event.outcome.trim());
        }
    });
    return Array.from(options).sort((left, right) => left.localeCompare(right));
};

export const useHistoryAuditFilters = () => {
    const [queryHistoryEntries, setQueryHistoryEntries] = useState<QueryHistoryEntryResponse[]>([]);
    const [loadingQueryHistory, setLoadingQueryHistory] = useState(false);
    const [historyDatasourceFilter, setHistoryDatasourceFilter] = useState('');
    const [historyStatusFilter, setHistoryStatusFilter] = useState('');
    const [historyFromFilter, setHistoryFromFilter] = useState('');
    const [historyToFilter, setHistoryToFilter] = useState('');
    const [historySortOrder, setHistorySortOrder] = useState<SortOrder>('newest');
    const [historyPageIndex, setHistoryPageIndex] = useState(0);
    const [historyPageSize, setHistoryPageSize] = useState(100);
    const [historyHasNextPage, setHistoryHasNextPage] = useState(false);

    const [auditEvents, setAuditEvents] = useState<AuditEventResponse[]>([]);
    const [loadingAuditEvents, setLoadingAuditEvents] = useState(false);
    const [auditTypeFilter, setAuditTypeFilter] = useState('');
    const [auditActorFilter, setAuditActorFilter] = useState('');
    const [auditOutcomeFilter, setAuditOutcomeFilter] = useState('');
    const [auditFromFilter, setAuditFromFilter] = useState('');
    const [auditToFilter, setAuditToFilter] = useState('');
    const [auditSortOrder, setAuditSortOrder] = useState<SortOrder>('newest');

    const auditActionOptions = useMemo(() => buildAuditActionOptions(auditEvents), [auditEvents]);
    const auditOutcomeOptions = useMemo(() => buildAuditOutcomeOptions(auditEvents), [auditEvents]);
    const sortedQueryHistoryEntries = useMemo(
        () => sortQueryHistoryEntries(queryHistoryEntries, historySortOrder),
        [historySortOrder, queryHistoryEntries]
    );
    const sortedAuditEvents = useMemo(
        () => sortAuditEvents(auditEvents, auditSortOrder),
        [auditEvents, auditSortOrder]
    );

    return {
        auditActionOptions,
        auditActorFilter,
        auditEvents,
        auditFromFilter,
        auditOutcomeFilter,
        auditOutcomeOptions,
        auditSortOrder,
        auditToFilter,
        auditTypeFilter,
        historyDatasourceFilter,
        historyFromFilter,
        historyHasNextPage,
        historyPageIndex,
        historyPageSize,
        historySortOrder,
        historyStatusFilter,
        historyToFilter,
        loadingAuditEvents,
        loadingQueryHistory,
        queryHistoryEntries,
        setAuditActorFilter,
        setAuditEvents,
        setAuditFromFilter,
        setAuditOutcomeFilter,
        setAuditSortOrder,
        setAuditToFilter,
        setAuditTypeFilter,
        setHistoryDatasourceFilter,
        setHistoryFromFilter,
        setHistoryHasNextPage,
        setHistoryPageIndex,
        setHistoryPageSize,
        setHistorySortOrder,
        setHistoryStatusFilter,
        setHistoryToFilter,
        setLoadingAuditEvents,
        setLoadingQueryHistory,
        setQueryHistoryEntries,
        sortedAuditEvents,
        sortedQueryHistoryEntries
    };
};
