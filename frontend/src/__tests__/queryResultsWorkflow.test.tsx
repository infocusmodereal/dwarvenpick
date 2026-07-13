import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useQueryResultsWorkflow } from '../workbench/useQueryResultsWorkflow';
import { buildWorkspaceTab } from '../workbench/useWorkspaceTabs';
import type { QueryResultsResponse, WorkspaceTab } from '../workbench/types';

const resultPayload: QueryResultsResponse = {
    executionId: 'exec-a',
    status: 'SUCCEEDED',
    columns: [{ name: 'value', jdbcType: 'VARCHAR' }],
    rows: [['ok']],
    pageSize: 500,
    nextPageToken: 'page-3',
    rowLimitReached: false
};

const tab = (
    id: string,
    executionId = '',
    executionStatus: WorkspaceTab['executionStatus'] = ''
): WorkspaceTab => ({
    ...buildWorkspaceTab('starrocks-prod-adhoc', id, 'select 1'),
    id,
    executionId,
    executionStatus
});

const jsonResponse = (payload: QueryResultsResponse) =>
    new Response(JSON.stringify(payload), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
    });

describe('useQueryResultsWorkflow', () => {
    const fetchMock = vi.fn<typeof fetch>();

    beforeEach(() => {
        fetchMock.mockReset();
        vi.stubGlobal('fetch', fetchMock);
    });

    afterEach(() => {
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
    });

    it('loads a completed background tab through the polling-facing fetch boundary', async () => {
        const activeTab = tab('tab-a');
        const backgroundTab = tab('tab-b', 'exec-b');
        const tabs: Record<string, WorkspaceTab> = {
            [activeTab.id]: activeTab,
            [backgroundTab.id]: backgroundTab
        };
        const updateWorkspaceTab = vi.fn(
            (tabId: string, updater: (currentTab: WorkspaceTab) => WorkspaceTab) => {
                tabs[tabId] = updater(tabs[tabId]);
            }
        );
        fetchMock.mockImplementation(async () => jsonResponse(resultPayload));

        const { result } = renderHook(() =>
            useQueryResultsWorkflow({
                activeTab,
                activeTabId: activeTab.id,
                onFeedback: vi.fn(),
                readFriendlyError: vi.fn(),
                updateWorkspaceTab
            })
        );

        await act(async () => {
            await result.current.fetchQueryResultsPage('tab-b', 'exec-b', 'page-2', []);
        });

        expect(fetchMock).toHaveBeenCalledWith(
            '/api/queries/exec-b/results?pageSize=500&pageToken=page-2',
            { method: 'GET', credentials: 'include' }
        );
        expect(updateWorkspaceTab).toHaveBeenCalledWith('tab-b', expect.any(Function));
        expect(tabs['tab-b']).toMatchObject({
            executionId: 'exec-b',
            resultColumns: resultPayload.columns,
            resultRows: resultPayload.rows,
            currentPageToken: 'page-2',
            nextPageToken: 'page-3',
            previousPageTokens: []
        });
        expect(tabs['tab-a'].resultRows).toEqual([]);
    });

    it('rejects a stale result response after the tab starts a newer execution', async () => {
        const activeTab = tab('tab-a');
        const backgroundTab = tab('tab-b', 'exec-new');
        const tabs: Record<string, WorkspaceTab> = { 'tab-b': backgroundTab };
        const updateWorkspaceTab = vi.fn(
            (tabId: string, updater: (currentTab: WorkspaceTab) => WorkspaceTab) => {
                tabs[tabId] = updater(tabs[tabId]);
            }
        );
        fetchMock.mockResolvedValue(jsonResponse(resultPayload));

        const { result } = renderHook(() =>
            useQueryResultsWorkflow({
                activeTab,
                activeTabId: activeTab.id,
                onFeedback: vi.fn(),
                readFriendlyError: vi.fn(),
                updateWorkspaceTab
            })
        );

        await act(async () => {
            await result.current.fetchQueryResultsPage('tab-b', 'exec-old');
        });

        expect(tabs['tab-b']).toEqual(backgroundTab);
    });

    it('preserves next and previous page token history', async () => {
        const activeTab = {
            ...tab('tab-a', 'exec-a'),
            currentPageToken: 'page-2',
            nextPageToken: 'page-3',
            previousPageTokens: ['', 'page-1']
        };
        const tabs: Record<string, WorkspaceTab> = { 'tab-a': activeTab };
        const updateWorkspaceTab = vi.fn(
            (tabId: string, updater: (currentTab: WorkspaceTab) => WorkspaceTab) => {
                tabs[tabId] = updater(tabs[tabId]);
            }
        );
        fetchMock.mockResolvedValue(jsonResponse(resultPayload));

        const { result, rerender } = renderHook(
            ({ currentTab }: { currentTab: WorkspaceTab }) =>
                useQueryResultsWorkflow({
                    activeTab: currentTab,
                    activeTabId: currentTab.id,
                    onFeedback: vi.fn(),
                    readFriendlyError: vi.fn(),
                    updateWorkspaceTab
                }),
            { initialProps: { currentTab: activeTab } }
        );

        act(() => result.current.view.onLoadNextResults());
        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
        expect(tabs['tab-a']).toMatchObject({
            currentPageToken: 'page-3',
            previousPageTokens: ['', 'page-1', 'page-2']
        });

        rerender({ currentTab: tabs['tab-a'] });
        fetchMock.mockImplementation(async () =>
            jsonResponse({ ...resultPayload, nextPageToken: '' })
        );
        act(() => result.current.view.onLoadPreviousResults());
        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
        expect(tabs['tab-a']).toMatchObject({
            currentPageToken: 'page-2',
            previousPageTokens: ['', 'page-1']
        });
    });

    it('refetches the active succeeded tab when page size changes', async () => {
        const activeTab = tab('tab-a', 'exec-a', 'SUCCEEDED');
        const updateWorkspaceTab = vi.fn();
        fetchMock.mockImplementation(async () => jsonResponse(resultPayload));

        const { result } = renderHook(() =>
            useQueryResultsWorkflow({
                activeTab,
                activeTabId: activeTab.id,
                onFeedback: vi.fn(),
                readFriendlyError: vi.fn(),
                updateWorkspaceTab
            })
        );

        await waitFor(() =>
            expect(fetchMock).toHaveBeenCalledWith(
                '/api/queries/exec-a/results?pageSize=500',
                expect.any(Object)
            )
        );
        act(() => result.current.view.onResultsPageSizeChange(100));
        await waitFor(() =>
            expect(fetchMock).toHaveBeenCalledWith(
                '/api/queries/exec-a/results?pageSize=100',
                expect.any(Object)
            )
        );
    });

    it('cycles result sorting and computes a bounded virtual row window', () => {
        const activeTab = {
            ...tab('tab-a'),
            resultRows: Array.from({ length: 100 }, (_, index) => [String(100 - index)])
        };
        const { result } = renderHook(() =>
            useQueryResultsWorkflow({
                activeTab,
                activeTabId: activeTab.id,
                onFeedback: vi.fn(),
                readFriendlyError: vi.fn(),
                updateWorkspaceTab: vi.fn()
            })
        );

        act(() => result.current.view.onToggleResultSort(0));
        expect(result.current.view.resultSortState).toEqual({ columnIndex: 0, direction: 'asc' });
        expect(result.current.view.visibleResultRows.rows[0]).toEqual(['1']);

        act(() => result.current.view.onToggleResultSort(0));
        expect(result.current.view.resultSortState).toEqual({ columnIndex: 0, direction: 'desc' });
        expect(result.current.view.visibleResultRows.rows[0]).toEqual(['100']);

        act(() => result.current.view.onResultGridScroll(31 * 20));
        expect(result.current.view.visibleResultRows.start).toBe(12);
        expect(result.current.view.visibleResultRows.rows.length).toBeLessThan(100);

        act(() => result.current.view.onToggleResultSort(0));
        expect(result.current.view.resultSortState).toBeNull();
    });

    it('applies an active sort only to the current server page', () => {
        const firstPage = {
            ...tab('tab-a'),
            currentPageToken: '',
            resultRows: [['3'], ['1']]
        };
        const { result, rerender } = renderHook(
            ({ activeTab }: { activeTab: WorkspaceTab }) =>
                useQueryResultsWorkflow({
                    activeTab,
                    activeTabId: activeTab.id,
                    onFeedback: vi.fn(),
                    readFriendlyError: vi.fn(),
                    updateWorkspaceTab: vi.fn()
                }),
            { initialProps: { activeTab: firstPage } }
        );

        act(() => result.current.view.onToggleResultSort(0));
        expect(result.current.view.visibleResultRows.rows).toEqual([['1'], ['3']]);

        rerender({
            activeTab: {
                ...firstPage,
                currentPageToken: 'page-2',
                previousPageTokens: [''],
                resultRows: [['4'], ['2']]
            }
        });

        expect(result.current.view.resultSortState).toEqual({ columnIndex: 0, direction: 'asc' });
        expect(result.current.view.visibleResultRows.rows).toEqual([['2'], ['4']]);
    });

    it('does not start an export without an execution ID', async () => {
        const onFeedback = vi.fn();
        const activeTab = tab('tab-a');
        const { result } = renderHook(() =>
            useQueryResultsWorkflow({
                activeTab,
                activeTabId: activeTab.id,
                onFeedback,
                readFriendlyError: vi.fn(),
                updateWorkspaceTab: vi.fn()
            })
        );

        await act(async () => result.current.view.onExportCsv());

        expect(onFeedback).toHaveBeenCalledWith('Run a query first to export CSV.');
        expect(fetchMock).not.toHaveBeenCalled();
        expect(result.current.view.exportingCsv).toBe(false);
    });

    it('downloads CSV with selected headers and closes the export menu on success', async () => {
        const onFeedback = vi.fn();
        const activeTab = tab('tab-a', 'exec-a');
        const createObjectURL = vi.fn<(blob: Blob) => string>(() => 'blob:query-export');
        const revokeObjectURL = vi.fn();
        Object.defineProperty(window.URL, 'createObjectURL', {
            configurable: true,
            value: createObjectURL
        });
        Object.defineProperty(window.URL, 'revokeObjectURL', {
            configurable: true,
            value: revokeObjectURL
        });
        const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
        fetchMock.mockResolvedValue(
            new Response('value\nok', {
                status: 200,
                headers: { 'Content-Disposition': 'attachment; filename="result.csv"' }
            })
        );

        const { result } = renderHook(() =>
            useQueryResultsWorkflow({
                activeTab,
                activeTabId: activeTab.id,
                onFeedback,
                readFriendlyError: vi.fn(),
                updateWorkspaceTab: vi.fn()
            })
        );
        act(() => {
            result.current.view.onToggleExportMenu();
            result.current.view.onExportIncludeHeadersChange(false);
            result.current.view.onToggleResultSort(0);
        });

        await act(async () => result.current.view.onExportCsv());

        expect(result.current.view.resultSortState).toEqual({ columnIndex: 0, direction: 'asc' });
        expect(fetchMock).toHaveBeenCalledWith('/api/queries/exec-a/export.csv?headers=false', {
            method: 'GET',
            credentials: 'include'
        });
        expect(createObjectURL).toHaveBeenCalledOnce();
        expect(createObjectURL.mock.calls[0][0]).toMatchObject({ size: 8 });
        expect(click).toHaveBeenCalledOnce();
        expect(revokeObjectURL).toHaveBeenCalledWith('blob:query-export');
        expect(onFeedback).toHaveBeenCalledWith('CSV export downloaded: result.csv');
        expect(result.current.view.showExportMenu).toBe(false);
        expect(result.current.view.exportingCsv).toBe(false);
    });

    it('keeps the menu open and preserves injected 401 handling on export failure', async () => {
        const activeTab = tab('tab-a', 'exec-a');
        const onFeedback = vi.fn();
        const navigate = vi.fn();
        const readFriendlyError = vi.fn(async (response: Response) => {
            if (response.status === 401) {
                navigate('/login');
            }
            return 'Session expired. Please sign in again.';
        });
        const createObjectURL = vi.fn();
        const revokeObjectURL = vi.fn();
        Object.defineProperty(window.URL, 'createObjectURL', {
            configurable: true,
            value: createObjectURL
        });
        Object.defineProperty(window.URL, 'revokeObjectURL', {
            configurable: true,
            value: revokeObjectURL
        });
        fetchMock.mockResolvedValue(new Response('', { status: 401 }));

        const { result } = renderHook(() =>
            useQueryResultsWorkflow({
                activeTab,
                activeTabId: activeTab.id,
                onFeedback,
                readFriendlyError,
                updateWorkspaceTab: vi.fn()
            })
        );
        act(() => result.current.view.onToggleExportMenu());
        await act(async () => result.current.view.onExportCsv());

        expect(readFriendlyError).toHaveBeenCalledWith(expect.objectContaining({ status: 401 }));
        expect(navigate).toHaveBeenCalledWith('/login');
        expect(onFeedback).toHaveBeenCalledWith('Session expired. Please sign in again.');
        expect(result.current.view.showExportMenu).toBe(true);
        expect(result.current.view.exportingCsv).toBe(false);
        expect(createObjectURL).not.toHaveBeenCalled();
        expect(revokeObjectURL).not.toHaveBeenCalled();
    });

    it('closes the export menu when clicking outside its ref boundary', () => {
        const activeTab = tab('tab-a', 'exec-a');

        const Harness = () => {
            const { view } = useQueryResultsWorkflow({
                activeTab,
                activeTabId: activeTab.id,
                onFeedback: vi.fn(),
                readFriendlyError: vi.fn(),
                updateWorkspaceTab: vi.fn()
            });
            return (
                <div>
                    <div ref={view.exportMenuRef}>
                        <button type="button" onClick={view.onToggleExportMenu}>
                            Toggle
                        </button>
                        {view.showExportMenu ? <span>Open</span> : null}
                    </div>
                    <button type="button">Outside</button>
                </div>
            );
        };

        render(<Harness />);
        fireEvent.click(screen.getByRole('button', { name: 'Toggle' }));
        expect(screen.getByText('Open')).toBeInTheDocument();
        fireEvent.mouseDown(screen.getByRole('button', { name: 'Outside' }));
        expect(screen.queryByText('Open')).not.toBeInTheDocument();
    });
});
