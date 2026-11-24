import {
    useCallback,
    useEffect,
    useState,
    useRef,
    useMemo,
    type Dispatch,
    type SetStateAction
} from 'react';
import type { DatasourceColumnEntryResponse, DatasourceSchemaBrowserResponse } from './types';

type ColumnState =
    | { status: 'loading' }
    | { status: 'ready'; columns: DatasourceColumnEntryResponse[] }
    | { status: 'error'; message: string };

export const explorerTableKey = (schema: string, table: string) => JSON.stringify([schema, table]);

async function fetchMetadata(
    datasourceId: string,
    params: Record<string, string>,
    signal: AbortSignal,
    readFriendlyError: Options['readFriendlyError']
): Promise<DatasourceSchemaBrowserResponse> {
    const url = `/api/datasources/${encodeURIComponent(datasourceId)}/schema-browser`;
    const query = new URLSearchParams({ stream: 'true', ...params });
    const response = await fetch(`${url}?${query}`, { credentials: 'include', signal });
    if (!response.ok) throw new Error(await readFriendlyError(response));
    const payload = (await response.json()) as DatasourceSchemaBrowserResponse | { error: string };
    if ('error' in payload) throw new Error(String(payload.error));
    if (payload.datasourceId !== datasourceId)
        throw new Error('Metadata belongs to a different connection.');
    return payload;
}

type Options = {
    datasourceId: string;
    searchActive?: boolean;
    readFriendlyError: (response: Response) => Promise<string>;
    setDatasourceHealthById: Dispatch<
        SetStateAction<Record<string, 'active' | 'inactive' | 'unknown'>>
    >;
};

export const useSchemaBrowser = ({
    datasourceId,
    searchActive = false,
    readFriendlyError,
    setDatasourceHealthById
}: Options) => {
    const [inventory, setSchemaBrowser] = useState<DatasourceSchemaBrowserResponse | null>(null);
    const [loadingSchemaBrowser, setLoadingSchemaBrowser] = useState(false);
    const [schemaBrowserError, setSchemaBrowserError] = useState('');
    const [columnStates, setColumnStates] = useState<Record<string, ColumnState>>({});
    const [fullMetadata, setFullMetadata] = useState<DatasourceSchemaBrowserResponse | null>(null);
    const [searchError, setSearchError] = useState('');
    const [searchLoading, setSearchLoading] = useState(false);
    const detailRequests = useRef(new Map<string, AbortController>());
    const activeRequest = useRef<AbortController | null>(null);
    const cancel = useCallback(() => {
        activeRequest.current?.abort();
        activeRequest.current = null;
        detailRequests.current.forEach((controller) => controller.abort());
        detailRequests.current.clear();
    }, []);

    const loadSchemaBrowser = useCallback(
        async (datasourceId: string, refresh = false) => {
            cancel();
            setColumnStates({});
            setFullMetadata(null);
            setSearchError('');
            setSearchLoading(false);
            const controller = new AbortController();
            activeRequest.current = controller;
            const isCurrent = () =>
                activeRequest.current === controller && !controller.signal.aborted;
            const normalizedDatasourceId = datasourceId.trim();
            if (!normalizedDatasourceId) {
                setSchemaBrowser(null);
                setSchemaBrowserError('');
                setLoadingSchemaBrowser(false);
                setDatasourceHealthById((current) => {
                    if (!datasourceId) {
                        return current;
                    }
                    return {
                        ...current,
                        [datasourceId]: 'unknown'
                    };
                });
                return;
            }

            setLoadingSchemaBrowser(true);
            setSchemaBrowserError('');
            try {
                const params: Record<string, string> = refresh
                    ? { includeColumns: 'false', refresh: 'true' }
                    : { includeColumns: 'false' };
                const payload = await fetchMetadata(
                    normalizedDatasourceId,
                    params,
                    controller.signal,
                    readFriendlyError
                );
                if (!isCurrent()) return;
                setSchemaBrowser(payload);
                setDatasourceHealthById((current) => ({
                    ...current,
                    [normalizedDatasourceId]: 'active'
                }));
            } catch (error) {
                if (!isCurrent()) return;
                const message =
                    error instanceof Error ? error.message : 'Failed to load schema browser.';
                setSchemaBrowser(null);
                setSchemaBrowserError(message);
                setDatasourceHealthById((current) => ({
                    ...current,
                    [normalizedDatasourceId]: 'inactive'
                }));
            } finally {
                if (isCurrent()) {
                    activeRequest.current = null;
                    setLoadingSchemaBrowser(false);
                }
            }
        },
        [cancel, readFriendlyError, setDatasourceHealthById]
    );

    useEffect(() => {
        setSchemaBrowser(null);
        void loadSchemaBrowser(datasourceId, false);
        return cancel;
    }, [datasourceId, loadSchemaBrowser, cancel]);

    const loadTableColumns = useCallback(
        async (schema: string, table: string) => {
            const key = explorerTableKey(schema, table);
            if (
                !inventory ||
                inventory.datasourceId !== datasourceId ||
                fullMetadata ||
                loadingSchemaBrowser
            )
                return;
            if (columnStates[key]?.status === 'ready' || detailRequests.current.has(key)) return;
            const controller = new AbortController();
            detailRequests.current.set(key, controller);
            setColumnStates((current) => ({ ...current, [key]: { status: 'loading' } }));
            try {
                const response = await fetchMetadata(
                    datasourceId,
                    { schema, table },
                    controller.signal,
                    readFriendlyError
                );
                if (controller.signal.aborted) return;
                const columns = response.schemas
                    .find((entry) => entry.schema === schema)
                    ?.tables.find((entry) => entry.table === table)?.columns;
                if (!columns) throw new Error('The table is no longer available.');
                setColumnStates((current) => ({ ...current, [key]: { status: 'ready', columns } }));
            } catch (error) {
                if (controller.signal.aborted) return;
                const message = error instanceof Error ? error.message : 'Unable to load columns.';
                setColumnStates((current) => ({ ...current, [key]: { status: 'error', message } }));
            } finally {
                if (detailRequests.current.get(key) === controller)
                    detailRequests.current.delete(key);
            }
        },
        [
            columnStates,
            datasourceId,
            fullMetadata,
            inventory,
            loadingSchemaBrowser,
            readFriendlyError
        ]
    );

    useEffect(() => {
        if (
            !searchActive ||
            !inventory ||
            inventory.datasourceId !== datasourceId ||
            loadingSchemaBrowser ||
            fullMetadata
        )
            return;
        const controller = new AbortController();
        setSearchError('');
        setSearchLoading(true);
        const requests = detailRequests.current;
        const timer = window.setTimeout(async () => {
            requests.set('search', controller);
            try {
                const response = await fetchMetadata(
                    datasourceId,
                    {},
                    controller.signal,
                    readFriendlyError
                );
                if (!controller.signal.aborted) setFullMetadata(response);
            } catch (error) {
                if (!controller.signal.aborted)
                    setSearchError(
                        error instanceof Error ? error.message : 'Unable to search columns.'
                    );
            } finally {
                if (requests.get('search') === controller) requests.delete('search');
                if (!controller.signal.aborted) setSearchLoading(false);
            }
        }, 250);
        return () => {
            window.clearTimeout(timer);
            controller.abort();
            if (requests.get('search') === controller) requests.delete('search');
            setSearchLoading(false);
        };
    }, [
        datasourceId,
        fullMetadata,
        inventory,
        loadingSchemaBrowser,
        readFriendlyError,
        searchActive
    ]);

    const schemaBrowser = useMemo(() => {
        if (!inventory || inventory.datasourceId !== datasourceId) return null;
        if (fullMetadata) return fullMetadata;
        return {
            ...inventory,
            schemas: inventory.schemas.map((schema) => ({
                ...schema,
                tables: schema.tables.map((table) => {
                    const state = columnStates[explorerTableKey(schema.schema, table.table)];
                    return state?.status === 'ready' ? { ...table, columns: state.columns } : table;
                })
            }))
        };
    }, [columnStates, datasourceId, fullMetadata, inventory]);

    return {
        schemaBrowser,
        loadingSchemaBrowser,
        schemaBrowserError,
        loadSchemaBrowser,
        columnStates,
        loadTableColumns,
        allColumnsLoaded: fullMetadata !== null,
        searchLoading,
        searchError
    };
};
