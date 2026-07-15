import path from 'node:path';

const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '::1']);
const REMOTE_EXPORT_EXPECTATIONS = new Set(['allowed', 'denied']);

const required = (value, name) => {
    const normalized = value?.trim();
    if (!normalized) {
        throw new Error(`${name} is required.`);
    }
    return normalized;
};

const canonicalHostname = (hostname, label) => {
    const normalized = hostname.replace(/^\[|\]$/g, '').toLowerCase();
    if (!normalized || normalized.endsWith('.')) {
        throw new Error(`${label} must use a canonical hostname without a trailing dot.`);
    }
    return normalized;
};

const parseBaseUrl = (rawValue) => {
    const raw = required(rawValue, 'BASE_URL');
    let url;
    try {
        url = new URL(raw);
    } catch {
        throw new Error('BASE_URL must be a valid absolute URL.');
    }

    if (url.username || url.password) {
        throw new Error('BASE_URL must not contain user information.');
    }
    if (url.pathname !== '/' || url.search || url.hash) {
        throw new Error('BASE_URL must point to the application origin without a path, query, or fragment.');
    }
    canonicalHostname(url.hostname, 'BASE_URL');
    return url;
};

const parseAllowedTarget = (rawEntry) => {
    const entry = required(rawEntry, 'BROWSER_SMOKE_ALLOWED_TARGETS entry');
    if (entry.includes('://') || entry.includes('/') || entry.includes('@')) {
        throw new Error('Allowed targets must use host or host:port syntax only.');
    }

    let parsed;
    try {
        parsed = new URL(`https://${entry}`);
    } catch {
        throw new Error(`Invalid allowed target '${entry}'.`);
    }

    if (parsed.pathname !== '/' || parsed.search || parsed.hash || parsed.username || parsed.password) {
        throw new Error(`Invalid allowed target '${entry}'.`);
    }

    return {
        hostname: canonicalHostname(parsed.hostname, 'Allowed target'),
        port: parsed.port || '443'
    };
};

const resolveReportDir = (value) =>
    path.resolve(value?.trim() || path.join(process.cwd(), '..', 'build', 'reports', 'browser-smoke'));

export const resolveBrowserSmokeConfig = (env = process.env) => {
    const mode = (env.BROWSER_SMOKE_MODE || 'local').trim().toLowerCase();
    if (mode !== 'local' && mode !== 'dev') {
        throw new Error("BROWSER_SMOKE_MODE must be 'local' or 'dev'.");
    }

    const baseUrl = parseBaseUrl(env.BASE_URL || 'http://localhost:3000');
    const hostname = canonicalHostname(baseUrl.hostname, 'BASE_URL');
    const reportDir = resolveReportDir(env.BROWSER_SMOKE_REPORT_DIR);

    if (mode === 'local') {
        if (!LOOPBACK_HOSTS.has(hostname)) {
            throw new Error('Local browser smoke only accepts a loopback BASE_URL.');
        }
        if (baseUrl.protocol !== 'http:' && baseUrl.protocol !== 'https:') {
            throw new Error('Local browser smoke requires HTTP or HTTPS.');
        }

        return {
            mode,
            baseUrl: baseUrl.origin,
            hostname,
            username: env.BROWSER_SMOKE_USER?.trim() || 'admin',
            password: env.BROWSER_SMOKE_PASSWORD || 'Admin1234!',
            datasource: 'mariadb-mart',
            credentialProfile: 'admin-ro',
            query:
                'SELECT order_item_id, order_id, product_id, quantity FROM warehouse.order_items ORDER BY order_item_id;',
            expectedExport: 'allowed',
            expectedRows: 18,
            reportDir
        };
    }

    if (env.BROWSER_SMOKE_ALLOW_REMOTE !== 'true') {
        throw new Error('Dev browser smoke requires BROWSER_SMOKE_ALLOW_REMOTE=true.');
    }
    if (baseUrl.protocol !== 'https:') {
        throw new Error('Dev browser smoke requires HTTPS.');
    }

    const allowedTargets = required(
        env.BROWSER_SMOKE_ALLOWED_TARGETS,
        'BROWSER_SMOKE_ALLOWED_TARGETS'
    )
        .split(',')
        .map(parseAllowedTarget);
    const targetPort = baseUrl.port || '443';
    if (
        !allowedTargets.some(
            (allowedTarget) =>
                allowedTarget.hostname === hostname && allowedTarget.port === targetPort
        )
    ) {
        throw new Error('BASE_URL is not an exact match for an allowed dev target.');
    }

    const expectedExport = required(
        env.BROWSER_SMOKE_EXPECT_EXPORT,
        'BROWSER_SMOKE_EXPECT_EXPORT'
    ).toLowerCase();
    if (!REMOTE_EXPORT_EXPECTATIONS.has(expectedExport)) {
        throw new Error("BROWSER_SMOKE_EXPECT_EXPORT must be 'allowed' or 'denied'.");
    }

    return {
        mode,
        baseUrl: baseUrl.origin,
        hostname,
        username: required(env.BROWSER_SMOKE_USER, 'BROWSER_SMOKE_USER'),
        password: required(env.BROWSER_SMOKE_PASSWORD, 'BROWSER_SMOKE_PASSWORD'),
        datasource: required(env.BROWSER_SMOKE_DATASOURCE, 'BROWSER_SMOKE_DATASOURCE'),
        credentialProfile: env.BROWSER_SMOKE_CREDENTIAL_PROFILE?.trim() || '',
        query: 'SELECT 1 AS browser_smoke;',
        expectedExport,
        expectedRows: 1,
        reportDir
    };
};

export const safeBrowserSmokeTargetSummary = (config) => ({
    mode: config.mode,
    origin: config.baseUrl,
    datasource: config.datasource,
    expectedExport: config.expectedExport
});
