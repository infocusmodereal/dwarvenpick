import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ConnectionsCatalog from '../workbench/sections/ConnectionsCatalog';
import type { ManagedDatasourceResponse } from '../workbench/types';
const defaults = {
    driverId: 'driver',
    driverClass: 'Driver',
    pool: { maximumPoolSize: 10, minimumIdle: 1, connectionTimeoutMs: 1000, idleTimeoutMs: 1000 },
    tls: { mode: 'DISABLE' as const, verifyServerCertificate: false, allowSelfSigned: false },
    tlsCertificates: { hasCaCertificate: false, hasClientCertificate: false, hasClientKey: false },
    options: {}
};
const connections: ManagedDatasourceResponse[] = [
    {
        ...defaults,
        id: 'pg',
        name: 'analytics',
        engine: 'POSTGRESQL',
        host: 'db.local',
        port: 5432,
        database: 'warehouse',
        credentialProfiles: []
    },
    {
        ...defaults,
        id: 'trino',
        name: 'lake',
        engine: 'TRINO',
        host: 'trino.local',
        port: 8080,
        credentialProfiles: []
    }
];
describe('Connections catalog', () => {
    it('filters locally by search and engine while preserving explicit edit and create actions', () => {
        const onEdit = vi.fn(),
            onCreate = vi.fn(),
            onDelete = vi.fn();
        render(
            <ConnectionsCatalog
                connections={connections}
                deleting={false}
                reencrypting={false}
                onCreate={onCreate}
                onEdit={onEdit}
                onDelete={onDelete}
                onReencrypt={vi.fn()}
            />
        );
        fireEvent.change(screen.getByLabelText('Filter connections by engine'), {
            target: { value: 'POSTGRESQL' }
        });
        expect(screen.queryByText('lake')).not.toBeInTheDocument();
        fireEvent.change(screen.getByLabelText('Search connections'), {
            target: { value: 'no match' }
        });
        expect(screen.getByText('No connections match these filters.')).toBeVisible();
        fireEvent.change(screen.getByLabelText('Search connections'), {
            target: { value: 'db.local' }
        });
        fireEvent.click(screen.getByRole('button', { name: 'Edit analytics' }));
        expect(onEdit).toHaveBeenCalledWith('pg');
        fireEvent.click(screen.getByRole('button', { name: 'Create Connection' }));
        expect(onCreate).toHaveBeenCalledOnce();
        expect(onDelete).not.toHaveBeenCalled();
    });
});
