import { render, screen } from '@testing-library/react';
import { IconButton, LabeledActionButton } from '../workbench/components/WorkbenchIcons';

describe('workbench action buttons', () => {
    it('gives primary actions a visible label and explanatory tooltip', () => {
        render(
            <LabeledActionButton
                icon="align-start-horizontal"
                label="Format SQL"
                title="Format SQL"
                onClick={() => undefined}
            />
        );

        const action = screen.getByRole('button', { name: 'Format SQL' });
        expect(action).toHaveAttribute('title', 'Format SQL');
        expect(action).toHaveTextContent('Format SQL');
    });

    it('keeps a stable accessible name and tooltip for compact icon actions', () => {
        render(<IconButton icon="info" title="Editor shortcuts" onClick={() => undefined} />);

        const action = screen.getByRole('button', { name: 'Editor shortcuts' });
        expect(action).toHaveAttribute('title', 'Editor shortcuts');
        expect(action).not.toHaveTextContent('Editor shortcuts');
    });
});
