import { act, renderHook } from '@testing-library/react';
import { useEffect, useRef, useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type {
    CatalogDatasourceResponse,
    QueryHistoryEntryResponse,
    WorkspaceSection,
    WorkspaceTab
} from '../workbench/types';
import { useHistoryEntryActions } from '../workbench/useHistoryEntryActions';

const datasource = (id: string, profiles = ['read-only']) =>
    ({ id, name: id, credentialProfiles: profiles }) as CatalogDatasourceResponse;

const historyEntry = (overrides: Partial<QueryHistoryEntryResponse> = {}) =>
    ({
        executionId: 'exec-1',
        actor: 'analyst',
        datasourceId: 'starrocks-dev-adhoc',
        status: 'SUCCEEDED',
        message: 'ok',
        queryHash: 'hash',
        queryText: 'select 1',
        queryTextRedacted: false,
        rowCount: 1,
        columnCount: 1,
        rowLimitReached: false,
        maxRowsPerQuery: 500,
        maxRuntimeSeconds: 60,
        credentialProfile: 'read-only',
        defaultSchema: 'Viper2',
        submittedAt: '2026-07-22T00:00:00Z',
        ...overrides
    }) as QueryHistoryEntryResponse;

const useHarness = (
    visibleDatasources: CatalogDatasourceResponse[],
    executeSqlForTab: (tabId: string, sqlText: string, mode: 'all') => Promise<void>,
    onError: (message: string) => void,
    onFeedback: (message: string) => void
) => {
    const [tabs, setTabs] = useState<WorkspaceTab[]>([]);
    const [activeTabId, setActiveTabId] = useState('');
    const [activeSection, setActiveSection] = useState<WorkspaceSection>('history');
    const workspaceTabsRef = useRef<WorkspaceTab[]>([]);
    useEffect(() => {
        workspaceTabsRef.current = tabs;
    }, [tabs]);
    const actions = useHistoryEntryActions({
        executeSqlForTab,
        onError,
        onFeedback,
        setActiveSection,
        setActiveTabId,
        setWorkspaceTabs: setTabs,
        visibleDatasources,
        workspaceTabsRef
    });
    return { ...actions, activeSection, activeTabId, tabs };
};

afterEach(() => {
    vi.useRealTimers();
});

describe('useHistoryEntryActions', () => {
    it('opens restored history without executing it', () => {
        const executeSqlForTab = vi.fn().mockResolvedValue(undefined);
        const { result } = renderHook(() =>
            useHarness([datasource('starrocks-dev-adhoc')], executeSqlForTab, vi.fn(), vi.fn())
        );

        act(() => result.current.openHistoryEntry(historyEntry(), false));

        expect(result.current.tabs).toHaveLength(1);
        expect(result.current.tabs[0]).toMatchObject({
            datasourceId: 'starrocks-dev-adhoc',
            schema: 'Viper2',
            requestedCredentialProfile: 'read-only',
            queryText: 'select 1'
        });
        expect(result.current.activeTabId).toBe(result.current.tabs[0].id);
        expect(result.current.activeSection).toBe('workbench');
        expect(executeSqlForTab).not.toHaveBeenCalled();
    });

    it('reruns only on the live matching permitted datasource', async () => {
        vi.useFakeTimers();
        const executeSqlForTab = vi.fn().mockResolvedValue(undefined);
        const { result, rerender } = renderHook(
            ({ datasources }) => useHarness(datasources, executeSqlForTab, vi.fn(), vi.fn()),
            { initialProps: { datasources: [datasource('fallback')] } }
        );
        rerender({ datasources: [datasource('starrocks-dev-adhoc')] });

        act(() => result.current.openHistoryEntry(historyEntry(), true));
        await act(async () => vi.runAllTimersAsync());

        expect(executeSqlForTab).toHaveBeenCalledWith(result.current.tabs[0].id, 'select 1', 'all');
    });

    it('opens a safe fallback without rerunning and reports the context change', async () => {
        vi.useFakeTimers();
        const executeSqlForTab = vi.fn().mockResolvedValue(undefined);
        const onFeedback = vi.fn();
        const { result } = renderHook(() =>
            useHarness([datasource('fallback')], executeSqlForTab, vi.fn(), onFeedback)
        );

        act(() => result.current.openHistoryEntry(historyEntry(), true));
        await act(async () => vi.runAllTimersAsync());

        expect(result.current.tabs[0]).toMatchObject({
            datasourceId: 'fallback',
            schema: '',
            requestedCredentialProfile: ''
        });
        expect(executeSqlForTab).not.toHaveBeenCalled();
        expect(onFeedback).toHaveBeenCalledWith(expect.stringContaining('no longer available'));
    });

    it('rejects missing SQL and an empty permitted datasource set', () => {
        const onError = vi.fn();
        const executeSqlForTab = vi.fn().mockResolvedValue(undefined);
        const withDatasource = renderHook(() =>
            useHarness([datasource('starrocks-dev-adhoc')], executeSqlForTab, onError, vi.fn())
        );
        act(() =>
            withDatasource.result.current.openHistoryEntry(
                historyEntry({ queryText: undefined }),
                false
            )
        );
        expect(onError).toHaveBeenCalledWith('This history entry has no reusable SQL text.');

        const withoutDatasource = renderHook(() =>
            useHarness([], executeSqlForTab, onError, vi.fn())
        );
        act(() => withoutDatasource.result.current.openHistoryEntry(historyEntry(), true));
        expect(onError).toHaveBeenCalledWith('No permitted connection is available for rerun.');
    });
});
