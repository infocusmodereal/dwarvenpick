import {
    useCallback,
    useEffect,
    useState,
    useRef,
    type Dispatch,
    type SetStateAction
} from 'react';
import type { DatasourceSchemaBrowserResponse } from './types';

type Options = {
    datasourceId: string;
    readFriendlyError: (response: Response) => Promise<string>;
    setDatasourceHealthById: Dispatch<
        SetStateAction<Record<string, 'active' | 'inactive' | 'unknown'>>
    >;
};

export const useSchemaBrowser = ({
    datasourceId,
    readFriendlyError,
    setDatasourceHealthById
}: Options) => {
    const [schemaBrowser, setSchemaBrowser] = useState<DatasourceSchemaBrowserResponse | null>(
        null
    );
    const [loadingSchemaBrowser, setLoadingSchemaBrowser] = useState(false);
    const [schemaBrowserError, setSchemaBrowserError] = useState('');
    const activeRequest = useRef<AbortController | null>(null);
    const cancel = useCallback(() => {
        activeRequest.current?.abort();
        activeRequest.current = null;
    }, []);

    const loadSchemaBrowser = useCallback(
        async (datasourceId: string, refresh = false) => {
            cancel();
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
                const queryParams = new URLSearchParams({ stream: 'true' });
                if (refresh) {
                    queryParams.set('refresh', 'true');
                }

                const response = await fetch(
                    `/api/datasources/${encodeURIComponent(normalizedDatasourceId)}/schema-browser${
                        queryParams.toString() ? `?${queryParams.toString()}` : ''
                    }`,
                    {
                        method: 'GET',
                        credentials: 'include',
                        signal: controller.signal
                    }
                );
                if (!isCurrent()) return;
                if (!response.ok) {
                    throw new Error(await readFriendlyError(response));
                }

                const payload = (await response.json()) as DatasourceSchemaBrowserResponse;
                if (!isCurrent()) return;
                if ('error' in payload) {
                    throw new Error(String(payload.error));
                }
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

    return { schemaBrowser, loadingSchemaBrowser, schemaBrowserError, loadSchemaBrowser };
};
