import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useAuditEvents } from '../workbench/useAuditEvents';
import type { AuditEventResponse } from '../workbench/types';

const auditEvent = (type: string): AuditEventResponse => ({
    type,
    outcome: 'succeeded',
    details: {},
    timestamp: '2026-07-16T12:00:00Z'
});

const jsonResponse = (body: unknown, status = 200) =>
    ({
        ok: status >= 200 && status < 300,
        status,
        json: vi.fn().mockResolvedValue(body)
    }) as unknown as Response;

afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
});

describe('useAuditEvents', () => {
    it('does not request admin audit events until enabled', () => {
        const fetchMock = vi.fn();

        renderHook(() =>
            useAuditEvents({
                enabled: false,
                fetchImpl: fetchMock,
                readFriendlyError: vi.fn(),
                onError: vi.fn()
            })
        );

        expect(fetchMock).not.toHaveBeenCalled();
    });

    it('loads the latest trimmed filters with the existing request contract', async () => {
        const rows = [auditEvent('query.execute')];
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse(rows));
        const readFriendlyError = vi.fn();
        const onError = vi.fn();
        const { result, rerender } = renderHook(
            ({ enabled }) =>
                useAuditEvents({ enabled, fetchImpl: fetchMock, readFriendlyError, onError }),
            { initialProps: { enabled: false } }
        );

        act(() => {
            result.current.setAuditTypeFilter(' query.execute ');
            result.current.setAuditActorFilter(' ivan+ops@example.com ');
            result.current.setAuditOutcomeFilter(' succeeded ');
            result.current.setAuditFromFilter('2026-07-15T08:00');
            result.current.setAuditToFilter('2026-07-16T18:30');
        });
        rerender({ enabled: true });

        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
        await waitFor(() => expect(result.current.loadingAuditEvents).toBe(false));

        const [requestUrl, requestOptions] = fetchMock.mock.calls[0] as [string, RequestInit];
        const params = new URL(requestUrl, 'https://dwarvenpick.test').searchParams;
        expect(params.get('type')).toBe('query.execute');
        expect(params.get('actor')).toBe('ivan+ops@example.com');
        expect(params.get('outcome')).toBe('succeeded');
        expect(params.get('from')).toBe(new Date('2026-07-15T08:00').toISOString());
        expect(params.get('to')).toBe(new Date('2026-07-16T18:30').toISOString());
        expect(params.get('limit')).toBe('200');
        expect(requestOptions).toMatchObject({ method: 'GET', credentials: 'include' });
        expect(requestOptions.signal).toBeTruthy();
        expect(result.current.sortedAuditEvents).toEqual(rows);
        expect(onError).not.toHaveBeenCalled();
    });

    it('omits invalid dates and normalizes a malformed payload to no events', async () => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ events: [] }));
        const readFriendlyError = vi.fn();
        const onError = vi.fn();
        const { result, rerender } = renderHook(
            ({ enabled }) =>
                useAuditEvents({ enabled, fetchImpl: fetchMock, readFriendlyError, onError }),
            { initialProps: { enabled: false } }
        );

        act(() => {
            result.current.setAuditFromFilter('not-a-date');
            result.current.setAuditToFilter('still-not-a-date');
        });
        rerender({ enabled: true });

        await waitFor(() => expect(result.current.loadingAuditEvents).toBe(false));
        const [requestUrl] = fetchMock.mock.calls[0] as [string];
        const params = new URL(requestUrl, 'https://dwarvenpick.test').searchParams;
        expect(params.has('from')).toBe(false);
        expect(params.has('to')).toBe(false);
        expect(result.current.sortedAuditEvents).toEqual([]);
    });

    it('forwards friendly errors and clears loading for the active request', async () => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ error: 'nope' }, 503));
        const readFriendlyError = vi.fn().mockResolvedValue('Audit is temporarily unavailable.');
        const onError = vi.fn();
        const { result } = renderHook(() =>
            useAuditEvents({ enabled: true, fetchImpl: fetchMock, readFriendlyError, onError })
        );

        await waitFor(() =>
            expect(onError).toHaveBeenCalledWith('Audit is temporarily unavailable.')
        );
        expect(readFriendlyError).toHaveBeenCalledTimes(1);
        expect(result.current.loadingAuditEvents).toBe(false);
    });

    it('aborts stale requests and keeps only the latest response', async () => {
        let rejectFirstRequest: ((reason?: unknown) => void) | undefined;
        const firstRequest = new Promise<Response>((_resolve, reject) => {
            rejectFirstRequest = reject;
        });
        const latestRows = [auditEvent('query.cancel')];
        const fetchMock = vi
            .fn()
            .mockImplementationOnce((_url: string, options: RequestInit) => {
                options.signal?.addEventListener('abort', () => {
                    rejectFirstRequest?.(new DOMException('Aborted', 'AbortError'));
                });
                return firstRequest;
            })
            .mockResolvedValueOnce(jsonResponse(latestRows));
        const onError = vi.fn();
        const { result } = renderHook(() =>
            useAuditEvents({
                enabled: true,
                fetchImpl: fetchMock,
                readFriendlyError: vi.fn(),
                onError
            })
        );

        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
        act(() => result.current.setAuditActorFilter('latest-actor'));

        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
        await waitFor(() => expect(result.current.loadingAuditEvents).toBe(false));
        expect(result.current.sortedAuditEvents).toEqual(latestRows);
        expect(onError).not.toHaveBeenCalled();
    });

    it('keeps refresh stable and clears filters with exactly one reload', async () => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
        const readFriendlyError = vi.fn();
        const onError = vi.fn();
        const { result } = renderHook(() =>
            useAuditEvents({ enabled: true, fetchImpl: fetchMock, readFriendlyError, onError })
        );

        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
        const initialRefresh = result.current.loadAuditEvents;

        act(() => result.current.setAuditActorFilter('ivan'));
        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
        expect(result.current.loadAuditEvents).toBe(initialRefresh);

        act(() => result.current.clearAuditFilters());
        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
        const clearedParams = new URL(
            fetchMock.mock.calls[2][0] as string,
            'https://dwarvenpick.test'
        ).searchParams;
        expect(clearedParams.has('actor')).toBe(false);

        act(() => result.current.clearAuditFilters());
        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4));
        expect(onError).not.toHaveBeenCalled();
    });

    it('aborts the active request on unmount', async () => {
        let requestSignal: AbortSignal | undefined;
        const fetchMock = vi.fn<typeof fetch>((_input, options) => {
            requestSignal = options?.signal ?? undefined;
            return new Promise<Response>(() => undefined);
        });
        const { unmount } = renderHook(() =>
            useAuditEvents({
                enabled: true,
                fetchImpl: fetchMock,
                readFriendlyError: vi.fn(),
                onError: vi.fn()
            })
        );

        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
        unmount();

        expect(requestSignal?.aborted).toBe(true);
    });

    it('aborts and clears loading when admin access is disabled', async () => {
        let requestSignal: AbortSignal | undefined;
        const fetchMock = vi.fn<typeof fetch>((_input, options) => {
            requestSignal = options?.signal ?? undefined;
            return new Promise<Response>(() => undefined);
        });
        const readFriendlyError = vi.fn();
        const onError = vi.fn();
        const { result, rerender } = renderHook(
            ({ enabled }) =>
                useAuditEvents({ enabled, fetchImpl: fetchMock, readFriendlyError, onError }),
            { initialProps: { enabled: true } }
        );

        await waitFor(() => expect(result.current.loadingAuditEvents).toBe(true));
        rerender({ enabled: false });

        await waitFor(() => expect(result.current.loadingAuditEvents).toBe(false));
        expect(requestSignal?.aborted).toBe(true);
        expect(fetchMock).toHaveBeenCalledTimes(1);
        expect(onError).not.toHaveBeenCalled();
    });
});
