import { governedQueryDeniedFallback, readGovernedQueryError } from '../workbench/queryPolicyError';

describe('readGovernedQueryError', () => {
    it('preserves the sanitized backend read-only denial message', async () => {
        const response = new Response(
            JSON.stringify({
                error: 'Read-only mode is enabled for this datasource access mapping. Only SELECT-like statements are allowed.'
            }),
            {
                status: 403,
                headers: { 'Content-Type': 'application/json' }
            }
        );

        await expect(readGovernedQueryError(response)).resolves.toBe(
            'Read-only mode is enabled for this datasource access mapping. Only SELECT-like statements are allowed.'
        );
    });

    it('uses a policy-specific fallback without exposing malformed payloads', async () => {
        const response = new Response('<html>upstream failure</html>', { status: 403 });

        await expect(readGovernedQueryError(response)).resolves.toBe(governedQueryDeniedFallback);
    });
});
