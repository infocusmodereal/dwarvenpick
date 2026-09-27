import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { QueryValidationResponse } from '../types';
import { IconGlyph } from './WorkbenchIcons';

export default function SqlValidationControl({
    result,
    running,
    disabled,
    onValidate,
    onDismiss
}: {
    result: QueryValidationResponse | null;
    running: boolean;
    disabled: boolean;
    onValidate: () => void;
    onDismiss: () => void;
}) {
    const anchor = useRef<HTMLButtonElement>(null);
    const panel = useRef<HTMLDivElement>(null);
    const [position, setPosition] = useState({ top: 0, left: 0 });
    useEffect(() => {
        if (!result) return;
        const place = () => {
            const rect = anchor.current?.getBoundingClientRect();
            if (rect)
                setPosition({
                    top: Math.max(
                        12,
                        Math.min(
                            rect.bottom + 8,
                            window.innerHeight - (panel.current?.offsetHeight ?? 220) - 12
                        )
                    ),
                    left: Math.max(12, Math.min(rect.left, window.innerWidth - 432))
                });
        };
        const close = (event: MouseEvent) => {
            if (
                !panel.current?.contains(event.target as Node) &&
                !anchor.current?.contains(event.target as Node)
            )
                onDismiss();
        };
        const escape = (event: KeyboardEvent) => {
            if (event.key === 'Escape') {
                event.stopPropagation();
                onDismiss();
                anchor.current?.focus();
            }
        };
        place();
        window.addEventListener('resize', place);
        window.addEventListener('scroll', place, true);
        document.addEventListener('mousedown', close);
        document.addEventListener('keydown', escape, true);
        return () => {
            window.removeEventListener('resize', place);
            window.removeEventListener('scroll', place, true);
            document.removeEventListener('mousedown', close);
            document.removeEventListener('keydown', escape, true);
        };
    }, [result, onDismiss]);
    return (
        <>
            <button
                ref={anchor}
                type="button"
                className={`labeled-action-button sql-validate-button ${result ? (result.valid ? 'is-valid' : 'is-invalid') : ''}`}
                title="Validate the current statement or selection without executing it"
                aria-expanded={!!result}
                aria-controls={result ? 'sql-validation-feedback' : undefined}
                disabled={disabled || running}
                onClick={onValidate}
            >
                <IconGlyph icon="shield-check" />
                <span>{running ? 'Validating…' : 'Validate SQL'}</span>
            </button>
            {result &&
                createPortal(
                    <div
                        ref={panel}
                        id="sql-validation-feedback"
                        className={`sql-validation-feedback ${result.valid ? 'is-valid' : 'is-invalid'}`}
                        style={position}
                    >
                        <div className="sql-validation-heading">
                            <IconGlyph icon={result.valid ? 'shield-check' : 'close'} />
                            <strong>{result.valid ? 'SQL is valid' : 'Validation failed'}</strong>
                            <button
                                type="button"
                                aria-label="Dismiss SQL validation"
                                title="Dismiss"
                                onClick={onDismiss}
                            >
                                <IconGlyph icon="close" />
                            </button>
                        </div>
                        <div role={result.valid ? 'status' : 'alert'}>
                            <p>
                                {result.valid
                                    ? 'Statement or selection passed validation.'
                                    : result.message}
                            </p>
                            {!result.valid && result.line != null && (
                                <span className="sql-validation-location">
                                    Line {result.line}
                                    {result.column != null ? ` · Column ${result.column}` : ''}
                                </span>
                            )}
                        </div>
                        <small>No query was executed.</small>
                    </div>,
                    document.body
                )}
        </>
    );
}
