import { readGovernedQueryError } from './queryPolicyError';
import type { QueryExecutionResponse } from './types';

export async function submitWorkbenchQuery(
    payload: object,
    csrf: { headerName: string; token: string },
    readFriendlyError: (response: Response) => Promise<string>
): Promise<QueryExecutionResponse> {
    const key = Array.from(crypto.getRandomValues(new Uint8Array(16)))
        .map((byte) => byte.toString(16).padStart(2, '0'))
        .join('');
    const options: RequestInit = {
        method: 'POST',
        credentials: 'include',
        headers: {
            'Content-Type': 'application/json',
            [csrf.headerName]: csrf.token,
            'Idempotency-Key': key
        },
        body: JSON.stringify(payload)
    };
    const attempt = async () => {
        const response = await fetch('/api/queries', options);
        if (!response.ok) return { response };
        const execution = (await response.json()) as QueryExecutionResponse;
        if (!execution?.executionId) throw new Error('Missing query execution ID.');
        return { execution };
    };
    const result = await attempt().catch(() =>
        attempt().catch(() => {
            throw new Error(
                'Could not confirm submission. Check Query History and verify any writes before running again.'
            );
        })
    );
    if (result.execution) return result.execution;
    const { response } = result;
    const message =
        response.status === 403
            ? await readGovernedQueryError(response)
            : await readFriendlyError(response);
    throw new Error(
        [500, 502, 504].includes(response.status)
            ? `${message} Check Query History and verify any writes before running again.`
            : message
    );
}
