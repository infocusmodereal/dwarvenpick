import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useSchemaBrowser } from '../workbench/useSchemaBrowser';

const deferred = <T>() => {
    let resolve!: (value: T) => void;
    let reject!: (reason: unknown) => void;
    const promise = new Promise<T>((yes, no) => {
        resolve = yes;
        reject = no;
    });
    return { promise, resolve, reject };
};
const response = (datasourceId: string) =>
    ({ ok: true, json: async () => ({ datasourceId, schemas: [] }) }) as Response;
const readFriendlyError = async () => 'Metadata failed';
afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
});

describe('schema browser request ownership', () => {
    it('keeps the new connection when slow Trino finishes last', async () => {
        const slow = deferred<Response>();
        const fast = deferred<Response>();
        const fetchMock = vi
            .fn()
            .mockReturnValueOnce(slow.promise)
            .mockReturnValueOnce(fast.promise);
        vi.stubGlobal('fetch', fetchMock);
        const setDatasourceHealthById = vi.fn();
        const { result, rerender } = renderHook(
            ({ datasourceId }) =>
                useSchemaBrowser({ datasourceId, readFriendlyError, setDatasourceHealthById }),
            { initialProps: { datasourceId: 'trino' } }
        );
        rerender({ datasourceId: 'postgres' });
        await act(async () => fast.resolve(response('postgres')));
        expect(result.current.schemaBrowser?.datasourceId).toBe('postgres');
        await act(async () => slow.resolve(response('trino')));
        expect(result.current.schemaBrowser?.datasourceId).toBe('postgres');
        expect(fetchMock.mock.calls[0][1]?.signal?.aborted).toBe(true);
    });
});

describe('schema browser cancellation edge cases', () => {
    const setup = () => {
        const requests: ReturnType<typeof deferred<Response>>[] = [];
        const fetchMock = vi.fn<typeof fetch>(() => {
            const request = deferred<Response>();
            requests.push(request);
            return request.promise;
        });
        vi.stubGlobal('fetch', fetchMock);
        const setDatasourceHealthById = vi.fn();
        const hook = renderHook(
            ({ datasourceId }) =>
                useSchemaBrowser({ datasourceId, readFriendlyError, setDatasourceHealthById }),
            { initialProps: { datasourceId: 'trino' } }
        );
        return { ...hook, requests, fetchMock, setDatasourceHealthById };
    };

    it('ignores stale failures and finally while the replacement is loading', async () => {
        const { result, rerender, requests, setDatasourceHealthById } = setup();
        rerender({ datasourceId: 'postgres' });
        await act(async () => requests[0].reject(new Error('old failure')));
        expect(result.current.loadingSchemaBrowser).toBe(true);
        expect(result.current.schemaBrowserError).toBe('');
        expect(setDatasourceHealthById).not.toHaveBeenCalled();
        await act(async () => requests[1].resolve(response('postgres')));
        expect(result.current.loadingSchemaBrowser).toBe(false);
    });

    it('distinguishes consecutive requests for the same connection', async () => {
        const { result, rerender, requests, fetchMock } = setup();
        rerender({ datasourceId: 'postgres' });
        rerender({ datasourceId: 'trino' });
        await act(async () => requests[2].resolve(response('trino')));
        await act(async () => requests[0].reject(new Error('superseded trino')));
        expect(result.current.schemaBrowser?.datasourceId).toBe('trino');
        expect(result.current.schemaBrowserError).toBe('');
        expect(fetchMock.mock.calls[0][1]?.signal?.aborted).toBe(true);
        expect(fetchMock.mock.calls[1][1]?.signal?.aborted).toBe(true);
    });

    it('cancels manual refresh on connection switch and unmount', async () => {
        const { result, rerender, unmount, requests, fetchMock } = setup();
        await act(async () => requests[0].resolve(response('trino')));
        act(() => {
            void result.current.loadSchemaBrowser('trino', true);
        });
        expect(fetchMock.mock.calls[1][0]).toContain('refresh=true');
        rerender({ datasourceId: 'postgres' });
        expect(fetchMock.mock.calls[1][1]?.signal?.aborted).toBe(true);
        expect(result.current.schemaBrowser).toBeNull();
        unmount();
        expect(fetchMock.mock.calls[2][1]?.signal?.aborted).toBe(true);
    });

    it('clears selection and ignores a late result', async () => {
        const { result, rerender, requests, fetchMock } = setup();
        rerender({ datasourceId: '' });
        await act(async () => requests[0].resolve(response('trino')));
        expect(result.current.schemaBrowser).toBeNull();
        expect(result.current.loadingSchemaBrowser).toBe(false);
        expect(result.current.schemaBrowserError).toBe('');
        expect(fetchMock).toHaveBeenCalledTimes(1);
        expect(fetchMock.mock.calls[0][1]?.signal?.aborted).toBe(true);
    });

    it('ignores a response whose body finishes after cancellation', async () => {
        const { result, rerender, requests } = setup();
        const body = deferred<unknown>();
        await act(async () =>
            requests[0].resolve({ ok: true, json: () => body.promise } as Response)
        );
        rerender({ datasourceId: 'postgres' });
        await act(async () => requests[1].resolve(response('postgres')));
        await act(async () => body.resolve({ datasourceId: 'trino', schemas: [] }));
        expect(result.current.schemaBrowser?.datasourceId).toBe('postgres');
    });

    it('requests a single table and discards its columns on connection switch', async () => {
        const { result, rerender, requests, fetchMock } = setup();
        const responseWithColumns = (
            columns: { name: string; jdbcType: string; nullable: boolean }[]
        ) =>
            ({
                ok: true,
                json: async () => ({
                    datasourceId: 'trino',
                    schemas: [
                        {
                            schema: 'sales',
                            tables: [{ table: 'line_items', type: 'TABLE', columns }]
                        }
                    ]
                })
            }) as Response;
        expect(fetchMock.mock.calls[0][0]).toContain('schemasOnly=true');
        await act(async () => requests[0].resolve(responseWithColumns([])));
        act(() => {
            void result.current.loadTableColumns('sales', 'line_items');
            void result.current.loadTableColumns('sales', 'line_items');
        });
        expect(fetchMock).toHaveBeenCalledTimes(2);
        expect(fetchMock.mock.calls[1][0]).toContain('schema=sales&table=line_items');
        rerender({ datasourceId: 'postgres' });
        await act(async () => requests[2].resolve(response('postgres')));
        await act(async () =>
            requests[1].resolve(
                responseWithColumns([{ name: 'id', jdbcType: 'bigint', nullable: false }])
            )
        );
        expect(result.current.schemaBrowser?.datasourceId).toBe('postgres');
        expect(result.current.columnStates).toEqual({});
        expect(fetchMock.mock.calls[1][1]?.signal?.aborted).toBe(true);
    });

    it('requests an opened schema and debounces search without replacing the browse inventory', async () => {
        vi.useFakeTimers();
        const requests: ReturnType<typeof deferred<Response>>[] = [];
        const fetchMock = vi.fn<typeof fetch>(() => {
            const request = deferred<Response>();
            requests.push(request);
            return request.promise;
        });
        vi.stubGlobal('fetch', fetchMock);
        const setDatasourceHealthById = vi.fn();
        const { result, rerender } = renderHook(
            ({ search }) =>
                useSchemaBrowser({
                    datasourceId: 'trino',
                    search,
                    readFriendlyError,
                    setDatasourceHealthById
                }),
            { initialProps: { search: '' } }
        );
        const metadata = (schemas: unknown[]) =>
            ({
                ok: true,
                json: async () => ({ datasourceId: 'trino', schemas })
            }) as Response;
        await act(async () => requests[0].resolve(metadata([{ schema: 'sales', tables: [] }])));
        act(() => {
            void result.current.loadSchemaTables('sales');
            void result.current.loadSchemaTables('sales');
        });
        expect(fetchMock).toHaveBeenCalledTimes(2);
        expect(fetchMock.mock.calls[1][0]).toContain('stream=true&schema=sales');
        await act(async () =>
            requests[1].resolve(
                metadata([
                    {
                        schema: 'sales',
                        tables: [{ table: 'orders', type: 'TABLE', columns: [] }]
                    }
                ])
            )
        );
        rerender({ search: 'customer' });
        rerender({ search: 'customer_id' });
        expect(fetchMock).toHaveBeenCalledTimes(2);
        await act(async () => vi.advanceTimersByTimeAsync(250));
        expect(fetchMock.mock.calls[2][0]).toContain('search=customer_id');
        await act(async () => requests[2].resolve(metadata([{ schema: 'archive', tables: [] }])));
        expect(result.current.searchBrowser?.schemas[0].schema).toBe('archive');
        expect(result.current.schemaBrowser?.schemas[0].tables[0].table).toBe('orders');
    });

    it('shows streamed server errors instead of treating them as metadata', async () => {
        const { result, requests } = setup();
        await act(async () =>
            requests[0].resolve({
                ok: true,
                json: async () => ({ error: 'Schema browser load timed out.' })
            } as Response)
        );
        expect(result.current.schemaBrowser).toBeNull();
        expect(result.current.schemaBrowserError).toContain('timed out');
        expect(result.current.loadingSchemaBrowser).toBe(false);
    });
});
