import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import ObjectInspectorSectionContent from '../workbench/components/ObjectInspectorSectionContent';
import SystemHealthEngineView from '../workbench/systemHealth/SystemHealthEngineView';
import type { ObjectInspectorSectionResponse, SystemHealthResponse } from '../workbench/types';

const section = (
    overrides: Partial<ObjectInspectorSectionResponse>
): ObjectInspectorSectionResponse => ({
    id: 'section',
    title: 'Section',
    status: 'OK',
    ...overrides
});

describe('engine-specific robustness states', () => {
    it('renders Vertica health details and nodes without claiming unsupported UI', () => {
        const response: SystemHealthResponse = {
            datasourceId: 'vertica-prod',
            datasourceName: 'Vertica Prod',
            engine: 'VERTICA',
            credentialProfile: 'admin',
            checkedAt: '2026-07-10T12:00:00Z',
            status: 'OK',
            nodeCount: 1,
            healthyNodeCount: 1,
            details: {
                databaseName: 'warehouse',
                serverVersion: 'Vertica 24.1',
                loadBalancePolicy: 'ROUNDROBIN',
                databaseStartTime: '2026-07-10 10:00:00'
            },
            nodes: [
                {
                    name: 'v_node0001',
                    role: 'PERMANENT',
                    status: 'UP',
                    details: { address: '10.0.0.1' }
                }
            ]
        };

        render(<SystemHealthEngineView response={response} />);

        expect(screen.getByRole('heading', { name: 'Vertica Cluster' })).toBeInTheDocument();
        expect(screen.getByText('warehouse')).toBeInTheDocument();
        expect(screen.getByText('Vertica 24.1')).toBeInTheDocument();
        expect(screen.getByText('ROUNDROBIN')).toBeInTheDocument();
        expect(screen.getByText('v_node0001')).toBeInTheDocument();
    });

    it('renders unsupported inspector sections as a neutral explicit state', () => {
        render(
            <ObjectInspectorSectionContent
                section={section({
                    status: 'UNSUPPORTED',
                    message: 'Trino does not expose connector-independent size metadata.'
                })}
            />
        );

        expect(screen.getByRole('status')).toHaveTextContent(
            'Trino does not expose connector-independent size metadata.'
        );
        expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it.each([
        ['INSUFFICIENT_PRIVILEGES', 'Grant catalog metadata access.'],
        ['ERROR', 'Inspector query failed.']
    ] as const)('renders %s inspector sections as alerts', (status, message) => {
        render(<ObjectInspectorSectionContent section={section({ status, message })} />);

        expect(screen.getByRole('alert')).toHaveTextContent(message);
    });

    it('preserves OK text, key-value, and table rendering', () => {
        const { rerender } = render(
            <ObjectInspectorSectionContent
                section={section({ status: 'OK', kind: 'TEXT', text: 'CREATE TABLE example' })}
            />
        );
        expect(screen.getByText('CREATE TABLE example')).toBeInTheDocument();

        rerender(
            <ObjectInspectorSectionContent
                section={section({
                    status: 'OK',
                    kind: 'KEY_VALUES',
                    keyValues: [{ key: 'partitioned', value: 'false' }]
                })}
            />
        );
        expect(screen.getByRole('row', { name: 'partitioned false' })).toBeInTheDocument();

        rerender(
            <ObjectInspectorSectionContent
                section={section({
                    status: 'OK',
                    kind: 'TABLE',
                    table: { columns: ['name'], rows: [['adUnits']] }
                })}
            />
        );
        expect(screen.getByRole('columnheader', { name: 'name' })).toBeInTheDocument();
        expect(screen.getByRole('cell', { name: 'adUnits' })).toBeInTheDocument();
    });
});
