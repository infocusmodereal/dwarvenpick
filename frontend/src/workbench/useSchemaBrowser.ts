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

type LoadState<T> =
    { status: 'loading' } | { status: 'ready'; value: T } | { status: 'error'; message: string };
type SchemaTables = DatasourceSchemaBrowserResponse['schemas'][number]['tables'];

export const explorerSchemaKey = (schema: string) => JSON.stringify([schema]);
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
    search?: string;
    readFriendlyError: (response: Response) => Promise<string>;
    setDatasourceHealthById: Dispatch<
        SetStateAction<Record<string, 'active' | 'inactive' | 'unknown'>>
    >;
};

export const useSchemaBrowser = ({
    datasourceId,
    search = '',
    readFriendlyError,
    setDatasourceHealthById
}: Options) => {
    const [inventory, setSchemaBrowser] = useState<DatasourceSchemaBrowserResponse | null>(null);
    const [loadingSchemaBrowser, setLoadingSchemaBrowser] = useState(false);
    const [schemaBrowserError, setSchemaBrowserError] = useState('');
    const [columnStates, setColumnStates] = useState<
        Record<string, LoadState<DatasourceColumnEntryResponse[]>>
    >({});
    const [tableStates, setTableStates] = useState<Record<string, LoadState<SchemaTables>>>({});
    const [searchState, setSearchState] = useState<{
        term: string;
        result: LoadState<DatasourceSchemaBrowserResponse>;
    } | null>(null);
    const term = search.trim().toLowerCase();
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
            setTableStates({});
            setSearchState(null);
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
                    ? { schemasOnly: 'true', refresh: 'true' }
                    : { schemasOnly: 'true' };
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

    const loadDetail = useCallback(
        async <T>(
            key: string,
            params: Record<string, string>,
            setState: Dispatch<SetStateAction<Record<string, LoadState<T>>>>,
            extract: (response: DatasourceSchemaBrowserResponse) => T
        ) => {
            if (
                inventory?.datasourceId !== datasourceId ||
                loadingSchemaBrowser ||
                detailRequests.current.has(key)
            )
                return;
            const controller = new AbortController();
            detailRequests.current.set(key, controller);
            setState((current) => ({ ...current, [key]: { status: 'loading' } }));
            try {
                const response = await fetchMetadata(
                    datasourceId,
                    params,
                    controller.signal,
                    readFriendlyError
                );
                if (controller.signal.aborted) return;
                const value = extract(response);
                setState((current) => ({ ...current, [key]: { status: 'ready', value } }));
            } catch (error) {
                if (controller.signal.aborted) return;
                const message = error instanceof Error ? error.message : 'Unable to load metadata.';
                setState((current) => ({ ...current, [key]: { status: 'error', message } }));
            } finally {
                if (detailRequests.current.get(key) === controller)
                    detailRequests.current.delete(key);
            }
        },
        [datasourceId, inventory, loadingSchemaBrowser, readFriendlyError]
    );

    const loadSchemaTables = useCallback(
        (schema: string) => {
            const key = explorerSchemaKey(schema);
            if (tableStates[key]?.status === 'ready') return;
            return loadDetail(
                key,
                { schema },
                setTableStates,
                (response) =>
                    response.schemas.find((entry) => entry.schema === schema)?.tables ?? []
            );
        },
        [loadDetail, tableStates]
    );

    const loadTableColumns = useCallback(
        (schema: string, table: string) => {
            const key = explorerTableKey(schema, table);
            if (columnStates[key]?.status === 'ready') return;
            return loadDetail(key, { schema, table }, setColumnStates, (response) => {
                const columns = response.schemas
                    .find((entry) => entry.schema === schema)
                    ?.tables.find((entry) => entry.table === table)?.columns;
                if (!columns) throw new Error('The table is no longer available.');
                return columns;
            });
        },
        [columnStates, loadDetail]
    );

    useEffect(() => {
        if (!term || inventory?.datasourceId !== datasourceId || loadingSchemaBrowser) return;
        const controller = new AbortController();
        const requests = detailRequests.current;
        setSearchState({ term, result: { status: 'loading' } });
        requests.set('search', controller);
        const timer = window.setTimeout(async () => {
            try {
                const value = await fetchMetadata(
                    datasourceId,
                    { search: term },
                    controller.signal,
                    readFriendlyError
                );
                if (!controller.signal.aborted)
                    setSearchState({ term, result: { status: 'ready', value } });
            } catch (error) {
                if (!controller.signal.aborted) {
                    const message =
                        error instanceof Error ? error.message : 'Unable to search objects.';
                    setSearchState({ term, result: { status: 'error', message } });
                }
            } finally {
                if (requests.get('search') === controller) requests.delete('search');
            }
        }, 250);
        return () => {
            window.clearTimeout(timer);
            controller.abort();
            if (requests.get('search') === controller) requests.delete('search');
        };
    }, [datasourceId, inventory, loadingSchemaBrowser, readFriendlyError, term]);

    const schemaBrowser = useMemo(() => {
        if (!inventory || inventory.datasourceId !== datasourceId) return null;
        return {
            ...inventory,
            schemas: inventory.schemas.map((schema) => {
                const tableState = tableStates[explorerSchemaKey(schema.schema)];
                const tables = tableState?.status === 'ready' ? tableState.value : schema.tables;
                return {
                    ...schema,
                    tables: tables.map((table) => {
                        const state = columnStates[explorerTableKey(schema.schema, table.table)];
                        return state?.status === 'ready'
                            ? { ...table, columns: state.value }
                            : table;
                    })
                };
            })
        };
    }, [columnStates, datasourceId, inventory, tableStates]);

    const searchResult = searchState?.term === term ? searchState.result : null;
    return {
        schemaBrowser,
        loadingSchemaBrowser,
        schemaBrowserError,
        loadSchemaBrowser,
        tableStates,
        loadSchemaTables,
        columnStates,
        loadTableColumns,
        searchBrowser: searchResult?.status === 'ready' ? searchResult.value : null,
        searchLoading: Boolean(
            term && schemaBrowser && (!searchResult || searchResult.status === 'loading')
        ),
        searchError: searchResult?.status === 'error' ? searchResult.message : ''
    };
};
