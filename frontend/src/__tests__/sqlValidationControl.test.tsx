import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import SqlActionControl from '../workbench/components/SqlActionControl';

describe('SQL validation feedback', () => {
    it('anchors success to the validation action and supports dismissal by keyboard', () => {
        const onDismiss = vi.fn();
        const onValidate = vi.fn();
        render(
            <SqlActionControl
                result={{ valid: true, message: 'ok' }}
                running={false}
                disabled={false}
                onAction={onValidate}
                onDismiss={onDismiss}
            />
        );
        expect(screen.getByRole('button', { name: 'Validate SQL' })).toHaveAttribute(
            'aria-expanded',
            'true'
        );
        expect(screen.getByText('SQL is valid')).toBeInTheDocument();
        expect(screen.getByRole('status')).toHaveTextContent('passed validation');
        fireEvent.keyDown(document, { key: 'Escape' });
        expect(onDismiss).toHaveBeenCalledOnce();
        expect(onValidate).not.toHaveBeenCalled();
    });
    it('shows engine diagnostics without adding an execution error and clears pending feedback', () => {
        const props = { running: false, disabled: false, onAction: vi.fn(), onDismiss: vi.fn() };
        const { rerender } = render(
            <SqlActionControl
                {...props}
                result={{ valid: false, message: 'Unexpected FROM', line: 3, column: 8 }}
            />
        );
        expect(screen.getByRole('alert')).toHaveTextContent('Unexpected FROM');
        expect(screen.getByText('Line 3 · Column 8')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: 'Dismiss SQL validation' }));
        expect(props.onDismiss).toHaveBeenCalledOnce();
        rerender(<SqlActionControl {...props} running result={null} />);
        expect(screen.queryByRole('alert')).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Validating…' })).toBeDisabled();
    });
});

// Formatting must announce its own outcome, without validation-only claims.
describe('SQL formatting feedback', () => {
    it('renders formatting success and parser errors in the action popover', () => {
        const props = {
            action: 'format' as const,
            running: false,
            disabled: false,
            onAction: vi.fn(),
            onDismiss: vi.fn()
        };
        const { rerender } = render(
            <SqlActionControl
                {...props}
                result={{ valid: true, message: 'Formatting applied to the editor.' }}
            />
        );
        expect(screen.getByRole('button', { name: 'Format SQL' })).toHaveAttribute(
            'aria-controls',
            'sql-format-feedback'
        );
        expect(screen.getByRole('status')).toHaveTextContent('Formatting applied');
        expect(screen.queryByText('No query was executed.')).not.toBeInTheDocument();
        rerender(
            <SqlActionControl {...props} result={{ valid: false, message: 'Unexpected token' }} />
        );
        expect(screen.getByText('Formatting failed')).toBeInTheDocument();
        expect(screen.getByRole('alert')).toHaveTextContent('Unexpected token');
        fireEvent.click(screen.getByRole('button', { name: 'Dismiss SQL formatting' }));
        expect(props.onDismiss).toHaveBeenCalledOnce();
    });
});
