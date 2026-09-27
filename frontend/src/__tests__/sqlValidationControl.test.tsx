import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import SqlValidationControl from '../workbench/components/SqlValidationControl';

describe('SQL validation feedback', () => {
    it('anchors success to the validation action and supports dismissal by keyboard', () => {
        const onDismiss = vi.fn();
        const onValidate = vi.fn();
        render(
            <SqlValidationControl
                result={{ valid: true, message: 'ok' }}
                running={false}
                disabled={false}
                onValidate={onValidate}
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
        const props = { running: false, disabled: false, onValidate: vi.fn(), onDismiss: vi.fn() };
        const { rerender } = render(
            <SqlValidationControl
                {...props}
                result={{ valid: false, message: 'Unexpected FROM', line: 3, column: 8 }}
            />
        );
        expect(screen.getByRole('alert')).toHaveTextContent('Unexpected FROM');
        expect(screen.getByText('Line 3 · Column 8')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: 'Dismiss SQL validation' }));
        expect(props.onDismiss).toHaveBeenCalledOnce();
        rerender(<SqlValidationControl {...props} running result={null} />);
        expect(screen.queryByRole('alert')).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Validating…' })).toBeDisabled();
    });
});
