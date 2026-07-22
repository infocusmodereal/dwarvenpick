import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useQueryHistory } from '../workbench/useQueryHistory';
import type { QueryHistoryEntryResponse } from '../workbench/types';

const historyEntry = (executionId: string, submittedAt: string): QueryHistoryEntryResponse => ({
    executionId,
    actor: 'analyst',
    datasourceId: 'starrocks-prod-adhoc',
    status: 'SUCCEEDED',
    message: 'ok',
    queryHash: executionId,
    queryTextRedacted: false,
    rowCount: 1,
    columnCount: 1,
    rowLimitReached: false,
    maxRowsPerQuery: 500,
    maxRuntimeSeconds: 60,
    credentialProfile: 'read-only',
    submittedAt
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

describe('useQueryHistory', () => {
    it('does not request history until it is enabled', () => {
        const fetchMock = vi.fn();

        renderHook(() =>
            useQueryHistory({
                enabled: false,
                fetchImpl: fetchMock,
                readFriendlyError: vi.fn(),
                onError: vi.fn()
            })
        );

        expect(fetchMock).not.toHaveBeenCalled();
    });

    it('loads filtered pages, detects the next page, and avoids stable rerender loops', async () => {
        const rows = [
            historyEntry('exec-new', '2026-07-03T10:00:00Z'),
            historyEntry('exec-old', '2026-07-01T10:00:00Z'),
            historyEntry('exec-next', '2026-07-02T10:00:00Z')
        ];
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse(rows));
        const readFriendlyError = vi.fn();
        const onError = vi.fn();
        const { result, rerender } = renderHook(
            ({ enabled }) =>
                useQueryHistory({ enabled, fetchImpl: fetchMock, readFriendlyError, onError }),
            { initialProps: { enabled: false } }
        );

        act(() => {
            result.current.setHistoryDatasourceFilter(' starrocks-prod-adhoc ');
            result.current.setHistoryStatusFilter(' SUCCEEDED ');
            result.current.setHistoryFromFilter('2026-07-01T08:00');
            result.current.setHistoryToFilter('2026-07-04T18:30');
            result.current.setHistoryPageSize(2);
            result.current.setHistoryPageIndex(1);
            result.current.setHistorySortOrder('oldest');
        });
        rerender({ enabled: true });

        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
        await waitFor(() => expect(result.current.loadingQueryHistory).toBe(false));

        const [requestUrl, requestOptions] = fetchMock.mock.calls[0] as [string, RequestInit];
        const params = new URL(requestUrl, 'https://dwarvenpick.test').searchParams;
        expect(params.get('datasourceId')).toBe('starrocks-prod-adhoc');
        expect(params.get('status')).toBe('SUCCEEDED');
        expect(params.get('from')).toBe(new Date('2026-07-01T08:00').toISOString());
        expect(params.get('to')).toBe(new Date('2026-07-04T18:30').toISOString());
        expect(params.get('limit')).toBe('3');
        expect(params.get('offset')).toBe('2');
        expect(params.get('sort')).toBe('oldest');
        expect(requestOptions).toMatchObject({ method: 'GET', credentials: 'include' });
        expect(requestOptions.signal).toBeTruthy();
        expect(result.current.historyHasNextPage).toBe(true);
        expect(result.current.sortedQueryHistoryEntries.map((entry) => entry.executionId)).toEqual([
            'exec-old',
            'exec-new'
        ]);

        rerender({ enabled: true });
        expect(fetchMock).toHaveBeenCalledTimes(1);

        act(() => result.current.setHistoryStatusFilter('FAILED'));
        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
        await waitFor(() => expect(result.current.loadingQueryHistory).toBe(false));
        expect(onError).not.toHaveBeenCalled();
    });

    it('forwards friendly errors and always clears loading state', async () => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ error: 'nope' }, 503));
        const readFriendlyError = vi.fn().mockResolvedValue('History is temporarily unavailable.');
        const onError = vi.fn();
        const { result } = renderHook(() =>
            useQueryHistory({
                enabled: true,
                fetchImpl: fetchMock,
                readFriendlyError,
                onError
            })
        );

        await waitFor(() =>
            expect(onError).toHaveBeenCalledWith('History is temporarily unavailable.')
        );
        expect(readFriendlyError).toHaveBeenCalledTimes(1);
        expect(result.current.loadingQueryHistory).toBe(false);
        expect(result.current.queryHistoryError).toBe('History is temporarily unavailable.');
    });

    it('ignores an aborted stale response when filters change', async () => {
        let rejectFirstRequest: ((reason?: unknown) => void) | undefined;
        const firstRequest = new Promise<Response>((_resolve, reject) => {
            rejectFirstRequest = reject;
        });
        const latestRows = [historyEntry('exec-latest', '2026-07-04T10:00:00Z')];
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
        const readFriendlyError = vi.fn();
        const { result } = renderHook(() =>
            useQueryHistory({
                enabled: true,
                fetchImpl: fetchMock,
                readFriendlyError,
                onError
            })
        );

        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
        act(() => result.current.setHistoryDatasourceFilter('starrocks-prod-adhoc'));

        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
        await waitFor(() => expect(result.current.loadingQueryHistory).toBe(false));
        expect(result.current.sortedQueryHistoryEntries[0]?.executionId).toBe('exec-latest');
        expect(onError).not.toHaveBeenCalled();
    });

    it('resets server pagination for every filter, sort, and page-size action', async () => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
        const readFriendlyError = vi.fn();
        const onError = vi.fn();
        const { result } = renderHook(() =>
            useQueryHistory({
                enabled: true,
                fetchImpl: fetchMock,
                readFriendlyError,
                onError
            })
        );
        await waitFor(() => expect(result.current.loadingQueryHistory).toBe(false));

        const assertReset = async (action: () => void) => {
            act(() => {
                result.current.setHistoryPageIndex(3);
            });
            expect(result.current.historyPageIndex).toBe(3);
            act(action);
            expect(result.current.historyPageIndex).toBe(0);
            await waitFor(() => expect(result.current.loadingQueryHistory).toBe(false));
        };

        await assertReset(() =>
            result.current.changeHistoryDatasourceFilter('starrocks-prod-adhoc')
        );
        await assertReset(() => result.current.changeHistoryStatusFilter('FAILED'));
        await assertReset(() => result.current.changeHistoryFromFilter('2026-07-01T00:00'));
        await assertReset(() => result.current.changeHistoryToFilter('2026-07-02T00:00'));
        await assertReset(() => result.current.changeHistoryPageSize(10));
        await assertReset(() => result.current.toggleHistorySortOrder());
        await assertReset(() => result.current.clearHistoryFilters());
    });
});
