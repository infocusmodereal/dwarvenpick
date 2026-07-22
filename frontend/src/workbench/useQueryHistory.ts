import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { sortQueryHistoryEntries, type SortOrder } from './useHistoryAuditFilters';
import type { QueryHistoryEntryResponse } from './types';
import { toIsoTimestamp } from './utils';

type UseQueryHistoryOptions = {
    enabled: boolean;
    fetchImpl?: typeof fetch;
    readFriendlyError: (response: Response) => Promise<string>;
    onError?: (message: string) => void;
};

export const useQueryHistory = ({
    enabled,
    fetchImpl = fetch,
    readFriendlyError,
    onError
}: UseQueryHistoryOptions) => {
    const [queryHistoryEntries, setQueryHistoryEntries] = useState<QueryHistoryEntryResponse[]>([]);
    const [loadingQueryHistory, setLoadingQueryHistory] = useState(false);
    const [queryHistoryError, setQueryHistoryError] = useState('');
    const [historyDatasourceFilter, setHistoryDatasourceFilter] = useState('');
    const [historyStatusFilter, setHistoryStatusFilter] = useState('');
    const [historyFromFilter, setHistoryFromFilter] = useState('');
    const [historyToFilter, setHistoryToFilter] = useState('');
    const [historySortOrder, setHistorySortOrder] = useState<SortOrder>('newest');
    const [historyPageIndex, setHistoryPageIndex] = useState(0);
    const [historyPageSize, setHistoryPageSize] = useState(100);
    const [historyHasNextPage, setHistoryHasNextPage] = useState(false);
    const activeRequestRef = useRef<AbortController | null>(null);
    const requestSequenceRef = useRef(0);

    const loadQueryHistory = useCallback(async () => {
        if (!enabled) {
            return;
        }

        activeRequestRef.current?.abort();
        const controller = new AbortController();
        const requestSequence = requestSequenceRef.current + 1;
        requestSequenceRef.current = requestSequence;
        activeRequestRef.current = controller;
        setLoadingQueryHistory(true);
        setQueryHistoryError('');

        try {
            const queryParams = new URLSearchParams();
            if (historyDatasourceFilter.trim()) {
                queryParams.set('datasourceId', historyDatasourceFilter.trim());
            }
            if (historyStatusFilter.trim()) {
                queryParams.set('status', historyStatusFilter.trim());
            }

            const fromIso = toIsoTimestamp(historyFromFilter);
            if (fromIso) {
                queryParams.set('from', fromIso);
            }
            const toIso = toIsoTimestamp(historyToFilter);
            if (toIso) {
                queryParams.set('to', toIso);
            }
            queryParams.set('limit', String(historyPageSize + 1));
            queryParams.set('offset', String(historyPageIndex * historyPageSize));
            queryParams.set('sort', historySortOrder);

            const response = await fetchImpl(`/api/queries/history?${queryParams.toString()}`, {
                method: 'GET',
                credentials: 'include',
                signal: controller.signal
            });
            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            const payload = (await response.json()) as QueryHistoryEntryResponse[];
            if (requestSequence !== requestSequenceRef.current) {
                return;
            }

            const rows = Array.isArray(payload) ? payload : [];
            setHistoryHasNextPage(rows.length > historyPageSize);
            setQueryHistoryEntries(rows.slice(0, historyPageSize));
        } catch (error) {
            if (controller.signal.aborted || requestSequence !== requestSequenceRef.current) {
                return;
            }

            const message =
                error instanceof Error ? error.message : 'Failed to load query history.';
            setQueryHistoryError(message);
            onError?.(message);
        } finally {
            if (requestSequence === requestSequenceRef.current) {
                activeRequestRef.current = null;
                setLoadingQueryHistory(false);
            }
        }
    }, [
        enabled,
        fetchImpl,
        historyDatasourceFilter,
        historyFromFilter,
        historyPageIndex,
        historyPageSize,
        historySortOrder,
        historyStatusFilter,
        historyToFilter,
        onError,
        readFriendlyError
    ]);

    useEffect(() => {
        if (!enabled) {
            activeRequestRef.current?.abort();
            requestSequenceRef.current += 1;
            activeRequestRef.current = null;
            setLoadingQueryHistory(false);
            return undefined;
        }

        void loadQueryHistory();
        return () => {
            activeRequestRef.current?.abort();
            requestSequenceRef.current += 1;
            activeRequestRef.current = null;
        };
    }, [enabled, loadQueryHistory]);

    const sortedQueryHistoryEntries = useMemo(
        () => sortQueryHistoryEntries(queryHistoryEntries, historySortOrder),
        [historySortOrder, queryHistoryEntries]
    );

    const changeHistoryDatasourceFilter = useCallback((value: string) => {
        setHistoryPageIndex(0);
        setHistoryDatasourceFilter(value);
    }, []);
    const changeHistoryStatusFilter = useCallback((value: string) => {
        setHistoryPageIndex(0);
        setHistoryStatusFilter(value);
    }, []);
    const changeHistoryFromFilter = useCallback((value: string) => {
        setHistoryPageIndex(0);
        setHistoryFromFilter(value);
    }, []);
    const changeHistoryToFilter = useCallback((value: string) => {
        setHistoryPageIndex(0);
        setHistoryToFilter(value);
    }, []);
    const changeHistoryPageSize = useCallback((value: number) => {
        setHistoryPageIndex(0);
        setHistoryPageSize(value);
    }, []);
    const toggleHistorySortOrder = useCallback(() => {
        setHistoryPageIndex(0);
        setHistorySortOrder((current) => (current === 'newest' ? 'oldest' : 'newest'));
    }, []);
    const clearHistoryFilters = useCallback(() => {
        setHistoryPageIndex(0);
        setHistoryDatasourceFilter('');
        setHistoryStatusFilter('');
        setHistoryFromFilter('');
        setHistoryToFilter('');
    }, []);

    return {
        changeHistoryDatasourceFilter,
        changeHistoryFromFilter,
        changeHistoryPageSize,
        changeHistoryStatusFilter,
        changeHistoryToFilter,
        clearHistoryFilters,
        historyDatasourceFilter,
        historyFromFilter,
        historyHasNextPage,
        historyPageIndex,
        historyPageSize,
        queryHistoryError,
        historySortOrder,
        historyStatusFilter,
        historyToFilter,
        loadQueryHistory,
        loadingQueryHistory,
        setHistoryDatasourceFilter,
        setHistoryFromFilter,
        setHistoryPageIndex,
        setHistoryPageSize,
        setHistorySortOrder,
        setHistoryStatusFilter,
        setHistoryToFilter,
        sortedQueryHistoryEntries,
        toggleHistorySortOrder
    };
};
