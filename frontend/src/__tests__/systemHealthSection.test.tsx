import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import SystemHealthSection from '../workbench/sections/SystemHealthSection';

type Props = ComponentProps<typeof SystemHealthSection>;
const props = (): Props => ({
    hidden: false,
    visibleDatasources: [
        {
            id: 'pg',
            name: 'Postgres',
            engine: 'POSTGRESQL',
            credentialProfiles: ['admin'],
            sysadminCredentialProfiles: ['admin']
        }
    ],
    datasourceId: 'pg',
    onDatasourceChange: vi.fn(),
    credentialProfile: 'admin',
    onCredentialProfileChange: vi.fn(),
    loading: false,
    error: '',
    onRefresh: vi.fn(),
    response: {
        datasourceId: 'pg',
        datasourceName: 'Postgres',
        engine: 'POSTGRESQL',
        credentialProfile: 'admin',
        checkedAt: '2026-09-27T00:00:00Z',
        status: 'OK',
        nodeCount: 1,
        healthyNodeCount: 1,
        nodes: [{ name: 'node-1', role: 'primary', status: 'OK', details: { version: '17.2' } }],
        details: {}
    },
    controlPlane: {
        loading: false,
        error: '',
        windowSeconds: 300,
        onWindowSecondsChange: vi.fn(),
        actorFilter: '',
        onActorFilterChange: vi.fn(),
        autoRefresh: false,
        onAutoRefreshChange: vi.fn(),
        onRefresh: vi.fn(),
        onPause: vi.fn(),
        onResume: vi.fn(),
        onCancelAll: vi.fn(),
        onKillAll: vi.fn(),
        onCancelExecution: vi.fn(),
        onKillExecution: vi.fn(),
        onExportCsv: vi.fn(),
        response: {
            datasourceId: 'pg',
            datasourceName: 'Postgres',
            engine: 'POSTGRESQL',
            paused: false,
            fetchedAt: '2026-09-27T00:00:00Z',
            queuedCount: 0,
            runningCount: 1,
            pools: [],
            activeQueries: [
                {
                    executionId: 'exec-1',
                    actor: 'analyst',
                    datasourceId: 'pg',
                    credentialProfile: 'read-only',
                    status: 'RUNNING',
                    message: '',
                    queryHash: 'hash',
                    sqlPreview: 'select 2',
                    submittedAt: '2026-09-27T00:00:00Z',
                    durationMs: 500,
                    cancelRequested: false
                }
            ],
            latency: {
                windowSeconds: 300,
                sampleSize: 0,
                succeededCount: 0,
                failedCount: 0,
                canceledCount: 0,
                latestErrors: []
            }
        }
    }
});

describe('System Health inspection', () => {
    it('switches views without triggering connection operations and refreshes both sources explicitly', () => {
        const input = props();
        render(<SystemHealthSection {...input} />);
        expect(screen.getByText('PostgreSQL Cluster')).toBeVisible();
        fireEvent.click(screen.getByRole('button', { name: 'Query activity' }));
        expect(screen.queryByText('PostgreSQL Cluster')).not.toBeInTheDocument();
        expect(screen.getByText('select 2')).toBeVisible();
        fireEvent.click(screen.getByRole('button', { name: 'Connection pools' }));
        expect(screen.getByText('No pools reported.')).toBeVisible();
        expect(input.controlPlane.onPause).not.toHaveBeenCalled();
        expect(input.controlPlane.onKillAll).not.toHaveBeenCalled();
        fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
        expect(input.onRefresh).toHaveBeenCalledOnce();
        expect(input.controlPlane.onRefresh).toHaveBeenCalledOnce();
    });
    it('keeps query and bulk actions wired to explicit user gestures', () => {
        const input = props();
        render(<SystemHealthSection {...input} />);
        fireEvent.click(screen.getByRole('button', { name: 'Query activity' }));
        fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
        expect(input.controlPlane.onCancelExecution).toHaveBeenCalledWith('exec-1');
        fireEvent.click(screen.getByText('Manage connection'));
        fireEvent.click(screen.getByRole('button', { name: 'Pause connection' }));
        expect(input.controlPlane.onPause).toHaveBeenCalledOnce();
    });
    it('does not show previous-connection data or enable its management actions', () => {
        const input = props();
        input.datasourceId = 'other';
        render(<SystemHealthSection {...input} />);
        expect(screen.queryByText('node-1')).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: 'Query activity' }));
        fireEvent.click(screen.getByText('Manage connection'));
        expect(screen.getByRole('button', { name: 'Pause connection' })).toBeDisabled();
        expect(screen.queryByText('select 2')).not.toBeInTheDocument();
    });

    it('shows unavailable activity without an endless loading message', () => {
        const input = props();
        input.controlPlane.response = null;
        input.controlPlane.error = 'Service unavailable';
        render(<SystemHealthSection {...input} />);
        fireEvent.click(screen.getByRole('button', { name: 'Query activity' }));
        expect(screen.getByRole('alert')).toHaveTextContent('Service unavailable');
        expect(screen.getByText('Query activity is unavailable. Refresh to retry.')).toBeVisible();
        expect(screen.queryByText('Loading query activity...')).not.toBeInTheDocument();
    });
});
