import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { vi } from 'vitest';
import ExplorerControls from '../workbench/components/ExplorerControls';

describe('ExplorerControls', () => {
    it('filters the connection menu and selects the matching connection with the keyboard', async () => {
        const change = vi.fn();
        const user = userEvent.setup();
        const { container } = render(
            <ExplorerControls
                disabled={false}
                visibleDatasources={[
                    { id: 'orders', name: 'Orders', engine: 'MYSQL', credentialProfiles: [] },
                    { id: 'ledger', name: 'Ledger', engine: 'POSTGRESQL', credentialProfiles: [] }
                ]}
                datasourceHealthTone="unknown"
                datasourceHealthLabel="Unknown"
                searchQuery=""
                onDatasourceChange={change}
                onSearchQueryChange={vi.fn()}
            />
        );
        expect(
            container.querySelector('.explorer-toolbar-fields')?.lastElementChild
        ).toContainElement(screen.getByRole('textbox', { name: 'Search explorer objects' }));
        await user.click(screen.getByRole('button', { name: 'Active tab connection' }));
        await user.keyboard('led{Enter}');
        expect(change).toHaveBeenCalledWith('ledger');
        expect(screen.getByRole('button', { name: 'Active tab connection' })).toHaveFocus();
    });
});
