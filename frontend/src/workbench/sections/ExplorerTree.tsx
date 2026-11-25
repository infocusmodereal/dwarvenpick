import { useEffect, useState } from 'react';
import {
    ExplorerIcon,
    ExplorerInsertIcon,
    ExplorerInspectIcon
} from '../components/WorkbenchIcons';
import { chevronDownIcon, chevronRightIcon } from '../icons';
import { explorerSchemaKey, explorerTableKey, type useSchemaBrowser } from '../useSchemaBrowser';

type ExplorerTreeProps = Pick<
    ReturnType<typeof useSchemaBrowser>,
    | 'loadSchemaTables'
    | 'tableStates'
    | 'searchBrowser'
    | 'columnStates'
    | 'loadTableColumns'
    | 'schemaBrowser'
    | 'searchError'
    | 'searchLoading'
> & {
    engine?: string;
    activeSchema?: string;
    search: string;
    onCopy: (value: string) => void;
    onInsert: (value: string) => void;
    onInspect: (schema: string, name: string, type: string) => void;
};

const systemSchemasByEngine: Record<string, string[]> = {
    MYSQL: ['information_schema', 'performance_schema', 'mysql', 'sys'],
    MARIADB: ['information_schema', 'performance_schema', 'mysql', 'sys'],
    POSTGRESQL: ['information_schema', 'pg_catalog', 'pg_toast'],
    STARROCKS: ['information_schema', 'sys', '_statistics_'],
    TRINO: ['information_schema']
};

function TreeChevron({ open }: { open: boolean }) {
    return (
        <span
            className="explorer-icon-glyph"
            aria-hidden
            dangerouslySetInnerHTML={{ __html: open ? chevronDownIcon : chevronRightIcon }}
        />
    );
}

function TreeToggle({
    open,
    label,
    onClick
}: {
    open: boolean;
    label: string;
    onClick: () => void;
}) {
    return (
        <button
            type="button"
            className="explorer-toggle"
            aria-expanded={open}
            title={label}
            onClick={onClick}
        >
            <TreeChevron open={open} />
        </button>
    );
}

function ObjectName({
    name,
    kind,
    search,
    onClick
}: {
    name: string;
    kind: 'schema' | 'table' | 'column';
    search: string;
    onClick: () => void;
}) {
    const index = search ? name.toLowerCase().indexOf(search) : -1;
    return (
        <>
            <button
                type="button"
                className="explorer-item-main"
                onClick={onClick}
                aria-label={`Copy ${name}`}
            >
                <span className="explorer-item-icon">
                    <ExplorerIcon glyph={kind} />
                </span>
                <span className="explorer-item-title">
                    {index < 0 ? (
                        name
                    ) : (
                        <>
                            {name.slice(0, index)}
                            <span className="explorer-highlight">
                                {name.slice(index, index + search.length)}
                            </span>
                            {name.slice(index + search.length)}
                        </>
                    )}
                </span>
            </button>
            <span className="explorer-name-hover-card" role="tooltip">
                <span className="explorer-name-hover-text">{name}</span>
            </span>
        </>
    );
}

function InsertObject({
    kind,
    name,
    onInsert
}: {
    kind: 'schema' | 'table' | 'column';
    name: string;
    onInsert: (value: string) => void;
}) {
    return (
        <button
            type="button"
            className="explorer-insert-button"
            title={`Insert ${kind} into editor`}
            aria-label={`Insert ${kind} ${name}`}
            onClick={() => onInsert(name)}
        >
            <ExplorerInsertIcon />
        </button>
    );
}

export default function ExplorerTree({
    schemaBrowser,
    engine,
    activeSchema,
    search,
    columnStates,
    loadTableColumns,
    loadSchemaTables,
    tableStates,
    searchBrowser,
    searchError,
    searchLoading,
    onCopy,
    onInsert,
    onInspect
}: ExplorerTreeProps) {
    const [expandedSchemas, setExpandedSchemas] = useState<Record<string, boolean>>({});
    const [expandedTables, setExpandedTables] = useState<Record<string, boolean>>({});
    const [systemExpanded, setSystemExpanded] = useState(false);
    const [selected, setSelected] = useState('');
    const normalizedSearch = search.trim().toLowerCase();
    const schemas = (normalizedSearch ? searchBrowser : schemaBrowser)?.schemas;
    const isSystem = (schema: string) =>
        systemSchemasByEngine[engine ?? '']?.includes(schema.toLowerCase()) ?? false;
    const defaultSchema = activeSchema || schemas?.find(({ schema }) => !isSystem(schema))?.schema;
    const systemsOpen = Boolean(normalizedSearch) || systemExpanded;
    const columnsSupported = engine !== 'AEROSPIKE';

    useEffect(() => {
        for (const schema of schemas ?? []) {
            const key = explorerSchemaKey(schema.schema);
            if (
                systemSchemasByEngine[engine ?? '']?.includes(schema.schema.toLowerCase()) &&
                !systemsOpen
            )
                continue;
            const open =
                Boolean(normalizedSearch) ||
                (expandedSchemas[key] ?? schema.schema === defaultSchema);
            if (!open) continue;
            if (!normalizedSearch && !tableStates[key]) void loadSchemaTables(schema.schema);
            if (!columnsSupported) continue;
            for (const table of schema.tables) {
                const tableKey = explorerTableKey(schema.schema, table.table);
                if (
                    expandedTables[tableKey] &&
                    !columnStates[tableKey] &&
                    table.columns.length === 0
                )
                    void loadTableColumns(schema.schema, table.table);
            }
        }
    }, [
        columnStates,
        columnsSupported,
        defaultSchema,
        engine,
        expandedSchemas,
        expandedTables,
        loadSchemaTables,
        loadTableColumns,
        normalizedSearch,
        schemas,
        systemsOpen,
        tableStates
    ]);

    if (!schemaBrowser) return null;

    const copy = (key: string, name: string) => {
        setSelected(key);
        onCopy(name);
    };
    const rowClass = (key: string) =>
        selected === key ? 'explorer-node-row selected' : 'explorer-node-row';

    const renderSchema = (entry: NonNullable<typeof schemas>[number]) => {
        const schema = entry.schema;
        const schemaKey = explorerSchemaKey(schema);
        const tableState = tableStates[schemaKey];
        const open =
            Boolean(normalizedSearch) || (expandedSchemas[schemaKey] ?? schema === defaultSchema);
        return (
            <li key={schemaKey} className="explorer-node explorer-depth-1">
                <div className={rowClass(schemaKey)}>
                    <TreeToggle
                        open={open}
                        label={open ? 'Collapse schema' : 'Expand schema'}
                        onClick={() =>
                            setExpandedSchemas((current) => ({ ...current, [schemaKey]: !open }))
                        }
                    />
                    <div className="explorer-item">
                        <ObjectName
                            name={schema}
                            kind="schema"
                            search={normalizedSearch}
                            onClick={() => copy(schemaKey, schema)}
                        />
                        <span className="explorer-item-tail">
                            {(normalizedSearch ||
                                tableState?.status === 'ready' ||
                                entry.tables.length > 0) && (
                                <span className="explorer-item-count" title="Table count">
                                    {entry.tables.length}
                                </span>
                            )}
                            <InsertObject kind="schema" name={schema} onInsert={onInsert} />
                        </span>
                    </div>
                </div>
                {open && (
                    <ul className="explorer-children">
                        {!normalizedSearch && (!tableState || tableState.status === 'loading') && (
                            <li className="explorer-empty" role="status">
                                Loading tables...
                            </li>
                        )}
                        {!normalizedSearch && tableState?.status === 'error' && (
                            <li className="explorer-empty">
                                <span role="alert">{tableState.message}</span>{' '}
                                <button type="button" onClick={() => void loadSchemaTables(schema)}>
                                    Retry tables
                                </button>
                            </li>
                        )}
                        {!normalizedSearch &&
                            tableState?.status === 'ready' &&
                            entry.tables.length === 0 && (
                                <li className="explorer-empty">No tables found.</li>
                            )}
                        {entry.tables.map((table) => {
                            const key = explorerTableKey(schema, table.table);
                            const status = columnStates[key];
                            const columns =
                                table.columns.length > 0 || status?.status !== 'ready'
                                    ? table.columns
                                    : status.value;
                            const known = status?.status === 'ready' || columns.length > 0;
                            const autoExpand =
                                Boolean(normalizedSearch) &&
                                table.columns.some((column) =>
                                    column.name.toLowerCase().includes(normalizedSearch)
                                );
                            const expanded = expandedTables[key] ?? autoExpand;
                            const qualifiedName = `${schema}.${table.table}`;
                            return (
                                <li key={key} className="explorer-node explorer-depth-2">
                                    <div className={rowClass(key)}>
                                        {columnsSupported &&
                                        (expanded || !known || columns.length > 0) ? (
                                            <TreeToggle
                                                open={expanded}
                                                label={
                                                    expanded
                                                        ? 'Collapse table columns'
                                                        : 'Expand table columns'
                                                }
                                                onClick={() =>
                                                    setExpandedTables((current) => ({
                                                        ...current,
                                                        [key]: !expanded
                                                    }))
                                                }
                                            />
                                        ) : (
                                            <span className="explorer-toggle-spacer" />
                                        )}
                                        <div className="explorer-item">
                                            <ObjectName
                                                name={table.table}
                                                kind="table"
                                                search={normalizedSearch}
                                                onClick={() => copy(key, table.table)}
                                            />
                                            <span className="explorer-item-tail">
                                                {known && (
                                                    <span
                                                        className="explorer-item-count"
                                                        title="Column count"
                                                    >
                                                        {columns.length}
                                                    </span>
                                                )}
                                                <button
                                                    type="button"
                                                    className="explorer-inspect-button"
                                                    title="Inspect object details"
                                                    aria-label={`Inspect ${qualifiedName}`}
                                                    onClick={() => {
                                                        setSelected(key);
                                                        onInspect(schema, table.table, table.type);
                                                    }}
                                                >
                                                    <ExplorerInspectIcon />
                                                </button>
                                                <InsertObject
                                                    kind="table"
                                                    name={qualifiedName}
                                                    onInsert={onInsert}
                                                />
                                            </span>
                                        </div>
                                    </div>
                                    {expanded && (
                                        <ul className="explorer-children">
                                            {!known && status?.status !== 'error' && (
                                                <li className="explorer-empty" role="status">
                                                    Loading columns...
                                                </li>
                                            )}
                                            {!known && status?.status === 'error' && (
                                                <li className="explorer-empty">
                                                    <span role="alert">{status.message}</span>{' '}
                                                    <button
                                                        type="button"
                                                        onClick={() =>
                                                            void loadTableColumns(
                                                                schema,
                                                                table.table
                                                            )
                                                        }
                                                    >
                                                        Retry columns
                                                    </button>
                                                </li>
                                            )}
                                            {known && columns.length === 0 && (
                                                <li className="explorer-empty">
                                                    No columns found.
                                                </li>
                                            )}
                                            {columns.map((column) => {
                                                const columnKey = JSON.stringify([
                                                    schema,
                                                    table.table,
                                                    column.name
                                                ]);
                                                return (
                                                    <li
                                                        key={columnKey}
                                                        className="explorer-node explorer-depth-3"
                                                    >
                                                        <div className={rowClass(columnKey)}>
                                                            <span className="explorer-toggle-spacer" />
                                                            <div className="explorer-item">
                                                                <ObjectName
                                                                    name={column.name}
                                                                    kind="column"
                                                                    search={normalizedSearch}
                                                                    onClick={() =>
                                                                        copy(columnKey, column.name)
                                                                    }
                                                                />
                                                                <span className="explorer-item-tail">
                                                                    <span className="explorer-item-type">
                                                                        ({column.jdbcType})
                                                                    </span>
                                                                    <InsertObject
                                                                        kind="column"
                                                                        name={column.name}
                                                                        onInsert={onInsert}
                                                                    />
                                                                </span>
                                                            </div>
                                                        </div>
                                                    </li>
                                                );
                                            })}
                                        </ul>
                                    )}
                                </li>
                            );
                        })}
                    </ul>
                )}
            </li>
        );
    };

    const systems = schemas?.filter((entry) => isSystem(entry.schema)) ?? [];
    return (
        <ul className="explorer-tree explorer-root" role="tree">
            {searchLoading && (
                <li className="explorer-empty" role="status">
                    Searching objects...
                </li>
            )}
            {normalizedSearch && searchError && (
                <li className="explorer-empty" role="alert">
                    {searchError}
                </li>
            )}
            {normalizedSearch && searchBrowser?.searchTruncated && (
                <li className="explorer-empty" role="status">
                    Search reached the metadata limit. Browse a schema to see more.
                </li>
            )}
            {!searchLoading &&
                !searchError &&
                !searchBrowser?.searchTruncated &&
                schemas?.length === 0 && (
                    <li className="explorer-empty">
                        {normalizedSearch ? 'No matching objects.' : 'No schemas discovered.'}
                    </li>
                )}
            {schemas?.filter((entry) => !isSystem(entry.schema)).map(renderSchema)}
            {systems.length > 0 && (
                <li>
                    <button
                        type="button"
                        className="system-group"
                        aria-expanded={systemsOpen}
                        onClick={() => setSystemExpanded(!systemsOpen)}
                    >
                        <TreeChevron open={systemsOpen} /> System schemas{' '}
                        <span>{systems.length}</span>
                    </button>
                    {systemsOpen && <ul>{systems.map(renderSchema)}</ul>}
                </li>
            )}
        </ul>
    );
}
