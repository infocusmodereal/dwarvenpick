import { describe, expect, it } from 'vitest';
import { buildQueryExecutionPayload, buildQueryValidationPayload } from '../workbench/queryPayload';
import type { WorkspaceTab } from '../workbench/types';

const tabWithOverrides = (overrides: Partial<WorkspaceTab> = {}): WorkspaceTab =>
    ({
        schema: '',
        requestedCredentialProfile: '',
        queryJustification: '',
        ...overrides
    }) as WorkspaceTab;

describe('query payload builders', () => {
    it('includes default schema for query execution payloads', () => {
        const payload = buildQueryExecutionPayload(
            tabWithOverrides({
                schema: ' Viper2 ',
                requestedCredentialProfile: ' read-only ',
                queryJustification: ' UI smoke '
            }),
            'starrocks-prod-adhoc',
            'select * from adUnits limit 50',
            {
                includeCredentialProfile: true,
                modeLabel: 'all',
                scriptStopOnError: true,
                scriptTransactionMode: 'AUTOCOMMIT'
            }
        );

        expect(payload).toEqual({
            datasourceId: 'starrocks-prod-adhoc',
            sql: 'select * from adUnits limit 50',
            credentialProfile: 'read-only',
            justification: 'UI smoke',
            defaultSchema: 'Viper2'
        });
    });

    it('includes default schema for validation payloads', () => {
        const payload = buildQueryValidationPayload(
            tabWithOverrides({
                schema: 'Viper2'
            }),
            'starrocks-prod-adhoc',
            'select * from adUnits limit 50',
            false
        );

        expect(payload).toEqual({
            datasourceId: 'starrocks-prod-adhoc',
            sql: 'select * from adUnits limit 50',
            defaultSchema: 'Viper2'
        });
    });

    it('omits blank default schema and preserves script settings', () => {
        const payload = buildQueryExecutionPayload(
            tabWithOverrides({
                schema: '   '
            }),
            'starrocks-prod-adhoc',
            'select 1; select 2;',
            {
                includeCredentialProfile: false,
                modeLabel: 'script',
                scriptStopOnError: false,
                scriptTransactionMode: 'TRANSACTION'
            }
        );

        expect(payload).toEqual({
            datasourceId: 'starrocks-prod-adhoc',
            sql: 'select 1; select 2;',
            scriptMode: true,
            stopOnError: false,
            transactionMode: 'TRANSACTION'
        });
    });
});
