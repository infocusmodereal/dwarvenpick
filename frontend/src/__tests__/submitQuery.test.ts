import { afterEach, expect, it, vi } from 'vitest';
import { submitWorkbenchQuery } from '../workbench/submitQuery';

afterEach(() => vi.unstubAllGlobals());

it('uses the same execution key when the original submission response is lost', async () => {
    const fetch = vi
        .fn()
        .mockRejectedValueOnce(new TypeError('connection reset'))
        .mockResolvedValueOnce({
            ok: true,
            json: async () => ({ executionId: 'original' })
        });
    vi.stubGlobal('fetch', fetch);
    const result = await submitWorkbenchQuery(
        { sql: 'UPDATE customers SET active = FALSE' },
        { headerName: 'X-CSRF', token: 'local-token' },
        async () => 'Failed'
    );
    expect(result.executionId).toBe('original');
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(fetch.mock.calls[0][1].headers['Idempotency-Key']).toMatch(/^[a-f0-9]{32}$/);
    expect(fetch.mock.calls[0][1]).toEqual(fetch.mock.calls[1][1]);
});
