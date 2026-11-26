import {
    type ReactNode,
    useEffect,
    useId,
    useLayoutEffect,
    useRef,
    useState,
    type KeyboardEvent
} from 'react';
import { IconGlyph } from './WorkbenchIcons';

type PickerItem = { value: string; label: string; iconLabel?: string; icon?: string };
type Props = {
    noun: 'connection' | 'schema' | 'profile' | 'status' | 'page size' | 'engine';
    searchable?: boolean;
    placement?: 'bottom' | 'top';
    triggerLabel: string;
    value: string;
    items: PickerItem[];
    disabled?: boolean;
    children?: ReactNode;
    onChange: (value: string) => void;
};
export default function SearchablePicker({
    noun,
    placement = 'bottom',
    searchable = true,
    triggerLabel,
    value,
    items,
    disabled = false,
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
    const plural = {
        connection: 'connections',
        schema: 'schemas',
        profile: 'profiles',
        status: 'statuses',
        engine: 'engines',
        'page size': 'page sizes'
    }[noun];
    const selected = items.find((item) => item.value === value);
    useLayoutEffect(() => {
        if (!open || disabled || !popover.current || !trigger.current) return;
        const popup = popover.current;
        if (placement === 'top') {
            const bounds = trigger.current.getBoundingClientRect();
            Object.assign(popup.style, {
                left: `${bounds.left}px`,
                bottom: `${window.innerHeight - bounds.top + 4}px`,
                width: `${bounds.width}px`,
                maxHeight: `${Math.max(0, bounds.top - 8)}px`
            });
            popup.showPopover?.();
        }
        if (searchable) popup.querySelector('input')?.focus();
        else list.current?.focus();

        if (placement === 'top') return;
        const fitViewport = () => {
            popup.style.translate = '';
            const edge = document.documentElement.clientWidth - 8;
            const overflow = popup.getBoundingClientRect().right - edge;
            if (overflow > 0 && edge > 0) popup.style.translate = `-${overflow}px`;
        };
        fitViewport();
        window.addEventListener('resize', fitViewport);
        return () => window.removeEventListener('resize', fitViewport);
    }, [open, disabled, placement, searchable]);
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
    const activeOption = options[cursor] ? listId + '-' + cursor : undefined;
    function navigate(event: KeyboardEvent) {
        if (event.key === 'Escape') {
            event.preventDefault();
            close();
            return;
        }
        if (!options.length) return;
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            const offset = event.key === 'ArrowDown' ? 1 : -1;
            move((cursor + offset + options.length) % options.length);
        }
        if (!searchable && (event.key === 'Home' || event.key === 'End')) {
            event.preventDefault();
            move(event.key === 'Home' ? 0 : options.length - 1);
        }
        if ((event.key === 'Enter' || (!searchable && event.key === ' ')) && options[cursor]) {
            event.preventDefault();
            choose(options[cursor].value);
        }
    }

    return (
        <div
            className={children == null ? 'connection-switch compact-picker' : 'connection-switch'}
            onBlur={(e) => {
                if (!e.currentTarget.contains(e.relatedTarget)) setOpen(false);
            }}
        >
            <button
                type="button"
                className="connection-trigger"
                ref={trigger}
                aria-label={triggerLabel}
                aria-description={
                    selected?.iconLabel ?? (children == null ? selected?.label : undefined)
                }
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
                {children ?? (
                    <span className="picker-current-label">
                        {selected?.label ?? items[0]?.label ?? value}
                    </span>
                )}
                <span aria-hidden>
                    {children == null ? <IconGlyph icon="chevron-down" /> : '▾'}
                </span>
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
                    onKeyDown={navigate}
                >
                    {searchable && (
                        <input
                            role="combobox"
                            aria-label={`Find ${noun}`}
                            placeholder={`Find ${noun}…`}
                            aria-expanded="true"
                            aria-autocomplete="list"
                            aria-controls={listId}
                            aria-activedescendant={activeOption}
                            value={filter}
                            onChange={(e) => {
                                setFilter(e.target.value);
                                setCursor(0);
                            }}
                        />
                    )}
                    <ul
                        role="listbox"
                        aria-label={plural}
                        id={listId}
                        ref={list}
                        tabIndex={searchable ? undefined : -1}
                        aria-activedescendant={searchable ? undefined : activeOption}
                    >
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
