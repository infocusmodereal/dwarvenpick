import { describe, expect, it } from 'vitest';
import {
    prepareTabForQueryExecution,
    queryRunStatusMessage
} from '../workbench/queryExecutionState';
import {
    buildCsvExportUrl,
    buildResultPageUrl,
    csvExportFileName,
    nextResultPageRequest,
    previousResultPageRequest
} from '../workbench/queryResults';
import { buildWorkspaceTab } from '../workbench/useWorkspaceTabs';

describe('query workflow helpers', () => {
    it('prepares a tab for a new query execution without carrying stale results', () => {
        const tab = {
            ...buildWorkspaceTab('starrocks-prod-adhoc', 'Query 1', 'select 1'),
            executionId: 'exec-old',
            resultRows: [['old']],
            resultColumns: [{ name: 'old', type: 'varchar', jdbcType: 'VARCHAR', nullable: true }],
            nextPageToken: 'next',
            previousPageTokens: ['older']
        };

        const prepared = prepareTabForQueryExecution(tab, 'analyze', 'analyze');

        expect(prepared).toMatchObject({
            isExecuting: true,
            executionId: '',
            executionStatus: '',
            lastRunKind: 'analyze',
            resultRows: [],
            resultColumns: [],
            nextPageToken: '',
            previousPageTokens: [],
            statusMessage: 'Running analysis...',
            errorMessage: ''
        });
    });

    it('maps run modes to user-facing status messages', () => {
        expect(queryRunStatusMessage('selection')).toBe('Running selected SQL...');
        expect(queryRunStatusMessage('statement')).toBe('Running statement at cursor...');
        expect(queryRunStatusMessage('script')).toBe('Running script...');
        expect(queryRunStatusMessage('all')).toBe('Running full tab SQL...');
    });

    it('builds result page requests for forward and backward paging', () => {
        const tab = {
            ...buildWorkspaceTab('starrocks-prod-adhoc', 'Query 1', 'select 1'),
            executionId: 'exec-1',
            currentPageToken: 'page-2',
            nextPageToken: 'page-3',
            previousPageTokens: ['', 'page-1']
        };

        expect(nextResultPageRequest(tab)).toEqual({
            pageToken: 'page-3',
            previousPageTokens: ['', 'page-1', 'page-2']
        });
        expect(previousResultPageRequest(tab)).toEqual({
            pageToken: 'page-1',
            previousPageTokens: ['']
        });
    });

    it('builds result and export URLs consistently', () => {
        expect(buildResultPageUrl('exec-1', 250, 'page-2')).toBe(
            '/api/queries/exec-1/results?pageSize=250&pageToken=page-2'
        );
        expect(buildCsvExportUrl('exec-1', false)).toBe(
            '/api/queries/exec-1/export.csv?headers=false'
        );
    });

    it('extracts CSV export filenames from content disposition', () => {
        expect(csvExportFileName('attachment; filename="query-result.csv"', 'fallback.csv')).toBe(
            'query-result.csv'
        );
        expect(csvExportFileName(null, 'fallback.csv')).toBe('fallback.csv');
    });
});
