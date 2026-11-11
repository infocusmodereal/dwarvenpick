import { type ReactNode, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';

type PickerItem = { value: string; label: string; iconLabel?: string; icon?: string };
type Props = {
    noun: 'connection' | 'schema' | 'profile';
    placement?: 'bottom' | 'top';
    triggerLabel: string;
    value: string;
    items: PickerItem[];
    disabled: boolean;
    children: ReactNode;
    onChange: (value: string) => void;
};
export default function SearchablePicker({
    noun,
    placement = 'bottom',
    triggerLabel,
    value,
    items,
    disabled,
    children,
    onChange
}: Props) {
    const [open, setOpen] = useState(false);
    const [filter, setFilter] = useState('');
    const [cursor, setCursor] = useState(0);
    const trigger = useRef<HTMLButtonElement>(null);
    const list = useRef<HTMLUListElement>(null);
    const listId = useId();
    const popover = useRef<HTMLDivElement>(null);
    const plural = { connection: 'connections', schema: 'schemas', profile: 'profiles' }[noun];
    useLayoutEffect(() => {
        if (!open || disabled || placement !== 'top' || !popover.current || !trigger.current)
            return;
        const bounds = trigger.current.getBoundingClientRect();
        Object.assign(popover.current.style, {
            left: `${bounds.left}px`,
            bottom: `${window.innerHeight - bounds.top + 4}px`,
            width: `${bounds.width}px`,
            maxHeight: `${Math.max(0, bounds.top - 8)}px`
        });
        popover.current.showPopover();
        popover.current.querySelector('input')?.focus();
    }, [open, disabled, placement]);
    useEffect(() => {
        if (!open || placement !== 'top') return;
        const dismiss = () => setOpen(false);
        const onScroll = (event: Event) => {
            if (event.target instanceof Node && popover.current?.contains(event.target)) return;
            dismiss();
        };
        window.addEventListener('resize', dismiss);
        window.addEventListener('scroll', onScroll, true);
        return () => {
            window.removeEventListener('resize', dismiss);
            window.removeEventListener('scroll', onScroll, true);
        };
    }, [open, placement]);
    const options = items.filter((item) =>
        (item.label + ' ' + (item.iconLabel ?? ''))
            .toLowerCase()
            .includes(filter.trim().toLowerCase())
    );
    function close() {
        setOpen(false);
        trigger.current?.focus();
    }
    function choose(id: string) {
        close();
        onChange(id);
    }
    function move(index: number) {
        setCursor(index);
        list.current?.children[index]?.scrollIntoView?.({ block: 'nearest' });
    }
    return (
        <div
            className="connection-switch"
            onBlur={(e) => {
                if (!e.currentTarget.contains(e.relatedTarget)) setOpen(false);
            }}
        >
            <button
                type="button"
                className="connection-trigger"
                ref={trigger}
                aria-label={triggerLabel}
                aria-description={items.find((item) => item.value === value)?.iconLabel}
                aria-haspopup="dialog"
                aria-expanded={open && !disabled}
                disabled={disabled}
                onClick={() => {
                    setFilter('');
                    setCursor(
                        Math.max(
                            0,
                            items.findIndex((item) => item.value === value)
                        )
                    );
                    setOpen(!open);
                }}
            >
                {children}
                <span aria-hidden>▾</span>
            </button>
            {open && !disabled && (
                <div
                    role="dialog"
                    aria-label={`Choose ${noun}`}
                    className={
                        placement === 'top'
                            ? 'connection-popover profile-popover'
                            : 'connection-popover'
                    }
                    ref={popover}
                    popover={placement === 'top' ? 'manual' : undefined}
                    onKeyDown={(e) => {
                        if (e.key === 'Escape') {
                            e.preventDefault();
                            close();
                        }
                    }}
                >
                    <input
                        autoFocus
                        role="combobox"
                        aria-label={`Find ${noun}`}
                        placeholder={`Find ${noun}…`}
                        aria-expanded="true"
                        aria-autocomplete="list"
                        aria-controls={listId}
                        aria-activedescendant={options[cursor] ? listId + '-' + cursor : undefined}
                        value={filter}
                        onChange={(e) => {
                            setFilter(e.target.value);
                            setCursor(0);
                        }}
                        onKeyDown={(e) => {
                            if (!options.length) return;
                            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                                e.preventDefault();
                                move(
                                    (cursor + (e.key === 'ArrowDown' ? 1 : -1) + options.length) %
                                        options.length
                                );
                            }
                            if (e.key === 'Enter' && options[cursor]) {
                                e.preventDefault();
                                choose(options[cursor].value);
                            }
                        }}
                    />
                    <ul role="listbox" aria-label={plural} id={listId} ref={list}>
                        {options.map((item, i) => (
                            <li
                                key={item.value}
                                id={listId + '-' + i}
                                role="option"
                                aria-label={item.label}
                                aria-description={item.iconLabel}
                                aria-selected={value === item.value}
                                className={i === cursor ? 'highlighted' : ''}
                                onMouseDown={(e) => e.preventDefault()}
                                onClick={() => choose(item.value)}
                            >
                                {item.icon && (
                                    <img
                                        src={item.icon}
                                        alt={item.iconLabel ?? ''}
                                        title={item.iconLabel}
                                    />
                                )}
                                <span>{item.label}</span>
                                {value === item.value && <span aria-hidden>✓</span>}
                            </li>
                        ))}
                    </ul>
                    {!options.length && <p role="status">No matching {plural}</p>}
                </div>
            )}
        </div>
    );
}
