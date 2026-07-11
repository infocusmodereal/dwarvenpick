import { describe, expect, it } from 'vitest';
import { buildHistoryWorkspaceTab } from '../workbench/queryHistoryContext';
import { buildQueryExecutionPayload } from '../workbench/queryPayload';
import type { QueryHistoryEntryResponse } from '../workbench/types';

const historyEntry = (
    overrides: Partial<QueryHistoryEntryResponse> = {}
): QueryHistoryEntryResponse => ({
    executionId: 'exec-1',
    actor: 'analyst',
    datasourceId: 'starrocks-dev-adhoc',
    status: 'SUCCEEDED',
    message: 'ok',
    queryHash: 'hash',
    queryText: 'select * from adUnits limit 50',
    queryTextRedacted: false,
    rowCount: 50,
    columnCount: 4,
    rowLimitReached: false,
    maxRowsPerQuery: 5000,
    maxRuntimeSeconds: 300,
    credentialProfile: 'read-only',
    defaultSchema: ' Viper2 ',
    justification: ' approved investigation ',
    submittedAt: '2026-07-10T00:00:00Z',
    ...overrides
});

describe('query history execution context', () => {
    it('restores schema, profile, and justification for the original datasource', () => {
        const { tab, datasourceFallback } = buildHistoryWorkspaceTab(
            historyEntry(),
            'starrocks-dev-adhoc',
            ['read-only'],
            'History 1',
            'select * from adUnits limit 50'
        );

        expect(datasourceFallback).toBe(false);
        expect(tab).toMatchObject({
            datasourceId: 'starrocks-dev-adhoc',
            schema: 'Viper2',
            requestedCredentialProfile: 'read-only',
            queryJustification: 'approved investigation'
        });
        expect(
            buildQueryExecutionPayload(tab, tab.datasourceId, tab.queryText, {
                includeCredentialProfile: true,
                modeLabel: 'all',
                scriptStopOnError: true,
                scriptTransactionMode: 'AUTOCOMMIT'
            })
        ).toMatchObject({
            defaultSchema: 'Viper2',
            credentialProfile: 'read-only',
            justification: 'approved investigation'
        });
    });

    it('clears datasource-specific context when the historical datasource is unavailable', () => {
        const { tab, datasourceFallback } = buildHistoryWorkspaceTab(
            historyEntry(),
            'trino-warehouse',
            ['read-only'],
            'History 2',
            'select * from adUnits limit 50'
        );

        expect(datasourceFallback).toBe(true);
        expect(tab.datasourceId).toBe('trino-warehouse');
        expect(tab.schema).toBe('');
        expect(tab.requestedCredentialProfile).toBe('');
        expect(tab.queryJustification).toBe('approved investigation');
    });

    it('keeps legacy history entries without a default schema reusable', () => {
        const { tab } = buildHistoryWorkspaceTab(
            historyEntry({ defaultSchema: undefined }),
            'starrocks-dev-adhoc',
            ['read-only'],
            'History 3',
            'select 1'
        );

        expect(tab.schema).toBe('');
        expect(tab.requestedCredentialProfile).toBe('read-only');
    });

    it('uses automatic RBAC resolution when the historical profile is no longer available', () => {
        const { tab } = buildHistoryWorkspaceTab(
            historyEntry({ credentialProfile: 'retired-profile' }),
            'starrocks-dev-adhoc',
            ['read-only'],
            'History 4',
            'select 1'
        );

        expect(tab.schema).toBe('Viper2');
        expect(tab.requestedCredentialProfile).toBe('');
    });
});
