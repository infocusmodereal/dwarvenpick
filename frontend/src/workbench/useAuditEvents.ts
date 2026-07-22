import { useCallback, useEffect, useRef, useState } from 'react';
import type { AuditEventResponse } from './types';
import { useHistoryAuditFilters } from './useHistoryAuditFilters';
import { toIsoTimestamp } from './utils';

type UseAuditEventsOptions = {
    enabled: boolean;
    fetchImpl?: typeof fetch;
    readFriendlyError: (response: Response) => Promise<string>;
    onError?: (message: string) => void;
};

type AuditFilters = {
    actor: string;
    from: string;
    outcome: string;
    to: string;
    type: string;
};

export const useAuditEvents = ({
    enabled,
    fetchImpl = fetch,
    readFriendlyError,
    onError
}: UseAuditEventsOptions) => {
    const filters = useHistoryAuditFilters();
    const {
        auditActorFilter,
        auditFromFilter,
        auditOutcomeFilter,
        auditToFilter,
        auditTypeFilter,
        setAuditActorFilter,
        setAuditEvents,
        setAuditFromFilter,
        setAuditOutcomeFilter,
        setAuditSortOrder,
        setAuditToFilter,
        setAuditTypeFilter,
        setLoadingAuditEvents
    } = filters;
    const activeRequestRef = useRef<AbortController | null>(null);
    const requestSequenceRef = useRef(0);
    const [auditEventsError, setAuditEventsError] = useState('');
    const onErrorRef = useRef(onError);
    const readFriendlyErrorRef = useRef(readFriendlyError);
    const filtersRef = useRef<AuditFilters>({
        actor: '',
        from: '',
        outcome: '',
        to: '',
        type: ''
    });

    // Keep the request callback stable while reading the latest render's filters and handlers.
    filtersRef.current = {
        actor: auditActorFilter,
        from: auditFromFilter,
        outcome: auditOutcomeFilter,
        to: auditToFilter,
        type: auditTypeFilter
    };
    onErrorRef.current = onError;
    readFriendlyErrorRef.current = readFriendlyError;

    const loadAuditEvents = useCallback(async () => {
        if (!enabled) {
            return;
        }

        activeRequestRef.current?.abort();
        const controller = new AbortController();
        const requestSequence = requestSequenceRef.current + 1;
        requestSequenceRef.current = requestSequence;
        activeRequestRef.current = controller;
        setLoadingAuditEvents(true);
        setAuditEventsError('');

        try {
            const currentFilters = filtersRef.current;
            const queryParams = new URLSearchParams();
            if (currentFilters.type.trim()) {
                queryParams.set('type', currentFilters.type.trim());
            }
            if (currentFilters.actor.trim()) {
                queryParams.set('actor', currentFilters.actor.trim());
            }
            if (currentFilters.outcome.trim()) {
                queryParams.set('outcome', currentFilters.outcome.trim());
            }

            const fromIso = toIsoTimestamp(currentFilters.from);
            if (fromIso) {
                queryParams.set('from', fromIso);
            }
            const toIso = toIsoTimestamp(currentFilters.to);
            if (toIso) {
                queryParams.set('to', toIso);
            }
            queryParams.set('limit', '200');

            const response = await fetchImpl(`/api/admin/audit-events?${queryParams.toString()}`, {
                method: 'GET',
                credentials: 'include',
                signal: controller.signal
            });
            if (!response.ok) {
                throw new Error(await readFriendlyErrorRef.current(response));
            }

            const payload = (await response.json()) as AuditEventResponse[];
            if (requestSequence !== requestSequenceRef.current) {
                return;
            }
            setAuditEvents(Array.isArray(payload) ? payload : []);
        } catch (error) {
            if (controller.signal.aborted || requestSequence !== requestSequenceRef.current) {
                return;
            }
            const message = error instanceof Error ? error.message : 'Failed to load audit events.';
            setAuditEventsError(message);
            onErrorRef.current?.(message);
        } finally {
            if (requestSequence === requestSequenceRef.current) {
                activeRequestRef.current = null;
                setLoadingAuditEvents(false);
            }
        }
    }, [enabled, fetchImpl, setAuditEvents, setLoadingAuditEvents]);

    useEffect(() => {
        if (!enabled) {
            activeRequestRef.current?.abort();
            requestSequenceRef.current += 1;
            activeRequestRef.current = null;
            setLoadingAuditEvents(false);
            return;
        }

        void loadAuditEvents();
    }, [
        enabled,
        auditActorFilter,
        auditFromFilter,
        auditOutcomeFilter,
        auditToFilter,
        auditTypeFilter,
        setLoadingAuditEvents,
        loadAuditEvents
    ]);

    useEffect(
        () => () => {
            activeRequestRef.current?.abort();
            requestSequenceRef.current += 1;
            activeRequestRef.current = null;
        },
        []
    );

    const clearAuditFilters = useCallback(() => {
        const currentFilters = filtersRef.current;
        const hadFilters = Object.values(currentFilters).some((value) => value.length > 0);

        setAuditTypeFilter('');
        setAuditActorFilter('');
        setAuditOutcomeFilter('');
        setAuditFromFilter('');
        setAuditToFilter('');

        // Changed filter state reloads through the effect; an already-clear form needs a refresh.
        if (!hadFilters) {
            void loadAuditEvents();
        }
    }, [
        loadAuditEvents,
        setAuditActorFilter,
        setAuditFromFilter,
        setAuditOutcomeFilter,
        setAuditToFilter,
        setAuditTypeFilter
    ]);

    const toggleAuditSortOrder = useCallback(() => {
        setAuditSortOrder((current) => (current === 'newest' ? 'oldest' : 'newest'));
    }, [setAuditSortOrder]);

    return {
        ...filters,
        auditEventsError,
        clearAuditFilters,
        loadAuditEvents,
        toggleAuditSortOrder
    };
};
