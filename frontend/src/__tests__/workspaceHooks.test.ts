import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
    buildAuditActionOptions,
    buildAuditOutcomeOptions,
    sortAuditEvents,
    sortQueryHistoryEntries
} from '../workbench/useHistoryAuditFilters';
import { useControlPlaneState } from '../workbench/useControlPlaneState';
import { hydrateStoredWorkspaceTabs, toPersistentTab } from '../workbench/useWorkspaceTabs';
import type {
    AuditEventResponse,
    CatalogDatasourceResponse,
    QueryHistoryEntryResponse,
    WorkspaceTab
} from '../workbench/types';

const datasource = (id: string): CatalogDatasourceResponse => ({
    id,
    name: id,
    engine: 'STARROCKS',
    credentialProfiles: []
});

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

const auditEvent = (type: string, outcome: string, timestamp: string): AuditEventResponse => ({
    type,
    outcome,
    details: {},
    timestamp
});

describe('workspace hooks helpers', () => {
    it('hydrates tabs with permitted datasource fallback and runtime-only query fields reset', () => {
        const storedTab = {
            id: 'tab-1',
            title: 'Saved',
            datasourceId: 'removed-datasource',
            schema: 'Viper2',
            queryText: 'select * from adUnits',
            resourceScope: 'SHARED' as const,
            resourceTags: ['ops']
        };

        const hydrated = hydrateStoredWorkspaceTabs([datasource('starrocks-prod-adhoc')], {
            activeTabId: 'tab-1',
            tabs: [storedTab]
        });

        expect(hydrated.activeTabId).toBe('tab-1');
        expect(hydrated.tabs[0]).toMatchObject({
            datasourceId: 'starrocks-prod-adhoc',
            schema: 'Viper2',
            queryText: 'select * from adUnits',
            isExecuting: false,
            executionId: '',
            requestedCredentialProfile: ''
        });
    });

    it('serializes only persistent tab fields', () => {
        const tab = {
            id: 'tab-1',
            title: 'Query',
            datasourceId: 'starrocks-prod-adhoc',
            schema: 'Viper2',
            queryText: 'select 1',
            requestedCredentialProfile: 'admin',
            queryJustification: 'ops',
            isExecuting: true,
            statusMessage: 'running',
            errorMessage: '',
            lastRunKind: 'query',
            executionId: 'exec-1',
            executionStatus: 'RUNNING',
            queryHash: 'hash',
            resultColumns: [],
            resultRows: [],
            nextPageToken: '',
            currentPageToken: '',
            previousPageTokens: [],
            rowLimitReached: false,
            submittedAt: '',
            startedAt: '',
            completedAt: '',
            rowCount: 0,
            columnCount: 0,
            maxRowsPerQuery: 0,
            maxRuntimeSeconds: 0,
            credentialProfile: '',
            scriptSummary: null
        } satisfies WorkspaceTab;

        expect(toPersistentTab(tab)).toEqual({
            id: 'tab-1',
            title: 'Query',
            datasourceId: 'starrocks-prod-adhoc',
            schema: 'Viper2',
            queryText: 'select 1',
            resourceId: undefined,
            resourceTitle: undefined,
            resourceScope: undefined,
            resourceGroupId: undefined,
            resourceFolderPath: undefined,
            resourceTags: undefined,
            resourceAllowGroupEdit: undefined,
            resourceOwner: undefined
        });
    });

    it('sorts history and audit rows by selected timestamp order', () => {
        const olderHistory = historyEntry('exec-1', '2026-07-01T10:00:00Z');
        const newerHistory = historyEntry('exec-2', '2026-07-02T10:00:00Z');
        const olderAudit = auditEvent('query.execute', 'succeeded', '2026-07-01T10:00:00Z');
        const newerAudit = auditEvent('query.cancel', 'succeeded', '2026-07-02T10:00:00Z');

        expect(sortQueryHistoryEntries([olderHistory, newerHistory], 'newest')[0].executionId).toBe(
            'exec-2'
        );
        expect(sortQueryHistoryEntries([olderHistory, newerHistory], 'oldest')[0].executionId).toBe(
            'exec-1'
        );
        expect(sortAuditEvents([olderAudit, newerAudit], 'newest')[0].type).toBe('query.cancel');
    });

    it('builds audit filter options from built-ins and loaded rows', () => {
        const events = [
            auditEvent('query.execute', 'succeeded', '2026-07-01T10:00:00Z'),
            auditEvent('custom.audit', 'custom-outcome', '2026-07-01T10:00:01Z')
        ];

        expect(buildAuditActionOptions(events)).toContain('custom.audit');
        expect(buildAuditOutcomeOptions(events)).toContain('custom-outcome');
    });

    it('keeps control-plane state behind one hook boundary', () => {
        const { result } = renderHook(() => useControlPlaneState());

        expect(result.current.controlPlaneWindowSeconds).toBe(900);
        expect(result.current.controlPlaneAutoRefresh).toBe(true);
        expect(result.current.controlPlaneResponse).toBeNull();

        act(() => {
            result.current.setControlPlaneActorFilter('ivan');
            result.current.setControlPlaneAutoRefresh(false);
        });

        expect(result.current.controlPlaneActorFilter).toBe('ivan');
        expect(result.current.controlPlaneAutoRefresh).toBe(false);
    });
});
