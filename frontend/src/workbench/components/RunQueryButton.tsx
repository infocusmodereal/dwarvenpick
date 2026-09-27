import { useEffect, useRef, useState } from 'react';
import { IconGlyph } from './WorkbenchIcons';

type Props = {
    disabled: boolean;
    running: boolean;
    hasSelection: boolean;
    onRun: () => void;
    onSelection: () => void;
    onScript: () => void;
    onCancel: () => void;
};
export default function RunQueryButton(props: Props) {
    const [open, setOpen] = useState(false);
    const root = useRef<HTMLDivElement>(null);
    const trigger = useRef<HTMLButtonElement>(null);
    const shortcut = navigator.platform.includes('Mac') ? 'Cmd' : 'Ctrl';
    useEffect(() => {
        const close = (event: MouseEvent) => {
            if (!root.current?.contains(event.target as Node)) setOpen(false);
        };
        document.addEventListener('mousedown', close);
        return () => document.removeEventListener('mousedown', close);
    }, []);
    const run = (action: () => void) => {
        setOpen(false);
        action();
    };
    return (
        <div
            className="run-split"
            ref={root}
            onKeyDown={(event) => {
                if (event.key === 'Escape') {
                    setOpen(false);
                    trigger.current?.focus();
                }
                if (open && ['ArrowDown', 'ArrowUp'].includes(event.key)) {
                    event.preventDefault();
                    const items = Array.from(
                        root.current?.querySelectorAll<HTMLButtonElement>(
                            '[role="menuitem"]:not(:disabled)'
                        ) ?? []
                    );
                    const index = items.indexOf(document.activeElement as HTMLButtonElement);
                    items[
                        (index + (event.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length
                    ]?.focus();
                }
            }}
        >
            <button
                type="button"
                className="labeled-action-button primary"
                title={
                    props.running
                        ? 'Cancel running query'
                        : 'Run statement at cursor or selected SQL'
                }
                disabled={!props.running && props.disabled}
                onClick={props.running ? props.onCancel : props.onRun}
            >
                <IconGlyph icon={props.running ? 'close' : 'play'} />
                {props.running ? 'Cancel' : 'Run'}
            </button>
            <button
                type="button"
                ref={trigger}
                className="labeled-action-button primary run-menu-trigger"
                title="Run options"
                aria-label="Run options"
                aria-haspopup="menu"
                aria-expanded={open}
                disabled={props.disabled || props.running}
                onClick={() => setOpen(!open)}
            >
                <IconGlyph icon="chevron-down" />
            </button>
            {open && (
                <div className="workbench-popover run-popover" role="menu" aria-label="Run options">
                    <button type="button" role="menuitem" onClick={() => run(props.onRun)}>
                        <span className="run-menu-label">
                            <IconGlyph icon="play" />
                            Run statement
                        </span>
                        <span className="run-shortcut">
                            <kbd>{shortcut}</kbd>
                            <kbd>Enter</kbd>
                        </span>
                    </button>
                    <button
                        type="button"
                        role="menuitem"
                        disabled={!props.hasSelection}
                        onClick={() => run(props.onSelection)}
                    >
                        <span className="run-menu-label">
                            <IconGlyph icon="selection" />
                            Run selection
                        </span>
                    </button>
                    <button type="button" role="menuitem" onClick={() => run(props.onScript)}>
                        <span className="run-menu-label">
                            <IconGlyph icon="script" />
                            Run script
                        </span>
                        <span className="run-shortcut">
                            <kbd>Shift</kbd>
                            <kbd>{shortcut}</kbd>
                            <kbd>Enter</kbd>
                        </span>
                    </button>
                </div>
            )}
        </div>
    );
}
