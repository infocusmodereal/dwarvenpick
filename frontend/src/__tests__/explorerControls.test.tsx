import { render, screen } from '@testing-library/react';
import { vi } from 'vitest';
import ExplorerControls from '../workbench/components/ExplorerControls';

describe('ExplorerControls', () => {
    it('keeps search after access controls, immediately before the object hierarchy', () => {
        const { container } = render(
            <ExplorerControls
                activeDatasourceId=""
                activeSchema=""
                requestedCredentialProfile=""
                queryJustification=""
                canOverrideCredentialProfile={false}
                disabled={false}
                visibleDatasources={[]}
                availableSchemaNames={[]}
                datasourceHealthTone="unknown"
                datasourceHealthLabel="Unknown"
                datasourceIcon=""
                searchQuery=""
                justificationInputRef={null}
                onDatasourceChange={vi.fn()}
                onSchemaChange={vi.fn()}
                onProfileChange={vi.fn()}
                onJustificationChange={vi.fn()}
                onSearchQueryChange={vi.fn()}
            />
        );
        expect(
            container.querySelector('.explorer-toolbar-fields')?.lastElementChild
        ).toContainElement(screen.getByRole('textbox', { name: 'Search explorer objects' }));
        expect(screen.getByRole('button', { name: 'Show access and policy' })).toHaveAttribute(
            'aria-expanded',
            'false'
        );
    });
});
