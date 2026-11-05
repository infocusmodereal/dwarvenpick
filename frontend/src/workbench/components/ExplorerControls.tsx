import { useRef, useState } from 'react';
import type { CatalogDatasourceResponse } from '../types';
import { resolveDatasourceIcon } from '../icons';
import { IconGlyph } from './WorkbenchIcons';

type ExplorerControlsProps = {
    activeDatasource?: CatalogDatasourceResponse;
    disabled: boolean;
    visibleDatasources: CatalogDatasourceResponse[];
    datasourceHealthTone: string;
    datasourceHealthLabel: string;
    searchQuery: string;
    onDatasourceChange: (id: string) => void;
    onSearchQueryChange: (query: string) => void;
};

export default function ExplorerControls({
    activeDatasource,
    disabled,
    visibleDatasources,
    datasourceHealthTone,
    datasourceHealthLabel,
    searchQuery,
    onDatasourceChange,
    onSearchQueryChange
}: ExplorerControlsProps) {
    const [open, setOpen] = useState(false);
    const [filter, setFilter] = useState('');
    const [cursor, setCursor] = useState(0);
    const trigger = useRef<HTMLButtonElement>(null);
    const list = useRef<HTMLUListElement>(null);
    const options = visibleDatasources.filter((d) =>
        (d.name + ' ' + d.engine).toLowerCase().includes(filter.trim().toLowerCase())
    );
    function close() {
        setOpen(false);
        trigger.current?.focus();
    }
    function choose(id: string) {
        close();
        onDatasourceChange(id);
    }
    function move(index: number) {
        setCursor(index);
        list.current?.children[index]?.scrollIntoView?.({ block: 'nearest' });
    }
    return (
        <div className="explorer-toolbar-fields">
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
                    aria-label="Active tab connection"
                    aria-haspopup="dialog"
                    aria-expanded={open && !disabled}
                    disabled={disabled}
                    onClick={() => {
                        setFilter('');
                        setCursor(
                            Math.max(
                                0,
                                visibleDatasources.findIndex((d) => d.id === activeDatasource?.id)
                            )
                        );
                        setOpen(!open);
                    }}
                >
                    <img src={resolveDatasourceIcon(activeDatasource?.engine)} alt="" />
                    <span className="connection-identity">
                        <strong>{activeDatasource?.name ?? 'Select connection'}</strong>
                        <small>
                            <i
                                className={'editor-connection-health tone-' + datasourceHealthTone}
                            />
                            {datasourceHealthLabel}
                            {activeDatasource && ' · ' + activeDatasource.engine}
                        </small>
                    </span>
                    <span aria-hidden>▾</span>
                </button>
                {open && !disabled && (
                    <div
                        role="dialog"
                        aria-label="Choose connection"
                        className="connection-popover"
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
                            aria-label="Find connection"
                            placeholder="Find connection…"
                            aria-expanded="true"
                            aria-autocomplete="list"
                            aria-controls="connection-options"
                            aria-activedescendant={
                                options[cursor] ? 'connection-option-' + cursor : undefined
                            }
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
                                        (cursor +
                                            (e.key === 'ArrowDown' ? 1 : -1) +
                                            options.length) %
                                            options.length
                                    );
                                }
                                if (e.key === 'Enter' && options[cursor]) {
                                    e.preventDefault();
                                    choose(options[cursor].id);
                                }
                            }}
                        />
                        <ul
                            role="listbox"
                            aria-label="Connections"
                            id="connection-options"
                            ref={list}
                        >
                            {options.map((d, i) => (
                                <li
                                    key={d.id}
                                    id={'connection-option-' + i}
                                    role="option"
                                    aria-label={d.name}
                                    aria-selected={activeDatasource?.id === d.id}
                                    className={i === cursor ? 'highlighted' : ''}
                                    onMouseDown={(e) => e.preventDefault()}
                                    onClick={() => choose(d.id)}
                                >
                                    <img src={resolveDatasourceIcon(d.engine)} alt="" />
                                    <span>
                                        {d.name}
                                        <small>{d.engine}</small>
                                    </span>
                                    {activeDatasource?.id === d.id && <span aria-hidden>✓</span>}
                                </li>
                            ))}
                        </ul>
                        {!options.length && <p role="status">No matching connections</p>}
                    </div>
                )}
            </div>
            <div className="explorer-control-group">
                <div className="explorer-toolbar-control-row">
                    <div className="explorer-search-wrap">
                        <span className="explorer-search-icon" aria-hidden>
                            <IconGlyph icon="search" />
                        </span>
                        <input
                            id="explorer-search"
                            aria-label="Search explorer objects"
                            placeholder="Search objects"
                            value={searchQuery}
                            onChange={(event) => onSearchQueryChange(event.target.value)}
                        />
                        {searchQuery.trim() ? (
                            <button
                                type="button"
                                className="explorer-search-clear"
                                aria-label="Clear explorer search"
                                title="Clear search"
                                onClick={() => onSearchQueryChange('')}
                            >
                                <IconGlyph icon="close" />
                            </button>
                        ) : null}
                    </div>
                </div>
            </div>
        </div>
    );
}
