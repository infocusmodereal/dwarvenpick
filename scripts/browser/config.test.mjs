import assert from 'node:assert/strict';
import test from 'node:test';

import {
    resolveBrowserSmokeConfig,
    safeBrowserSmokeTargetSummary
} from './config.mjs';

const remoteEnv = (overrides = {}) => ({
    BROWSER_SMOKE_MODE: 'dev',
    BROWSER_SMOKE_ALLOW_REMOTE: 'true',
    BASE_URL: 'https://dwarvenpick-dev.example.com',
    BROWSER_SMOKE_ALLOWED_TARGETS: 'dwarvenpick-dev.example.com',
    BROWSER_SMOKE_USER: 'approved-user',
    BROWSER_SMOKE_PASSWORD: 'never-print-this',
    BROWSER_SMOKE_DATASOURCE: 'approved-read-only',
    BROWSER_SMOKE_EXPECT_EXPORT: 'allowed',
    ...overrides
});

test('local mode accepts loopback and uses a fixed bounded query', () => {
    const config = resolveBrowserSmokeConfig({ BASE_URL: 'http://127.0.0.1:3000' });

    assert.equal(config.mode, 'local');
    assert.equal(config.datasource, 'mariadb-mart');
    assert.equal(config.expectedRows, 18);
    assert.match(config.query, /^SELECT /);
});

test('local mode rejects non-loopback and URL credentials', () => {
    assert.throws(
        () => resolveBrowserSmokeConfig({ BASE_URL: 'http://example.com:3000' }),
        /loopback/
    );
    assert.throws(
        () => resolveBrowserSmokeConfig({ BASE_URL: 'http://user@example.com:3000' }),
        /user information/
    );
});

test('dev mode requires explicit opt-in, HTTPS, and exact host matching', () => {
    assert.throws(
        () =>
            resolveBrowserSmokeConfig(
                remoteEnv({ BROWSER_SMOKE_ALLOW_REMOTE: 'false' })
            ),
        /ALLOW_REMOTE=true/
    );
    assert.throws(
        () => resolveBrowserSmokeConfig(remoteEnv({ BASE_URL: 'http://dwarvenpick-dev.example.com' })),
        /requires HTTPS/
    );
    assert.throws(
        () =>
            resolveBrowserSmokeConfig(
                remoteEnv({ BASE_URL: 'https://dwarvenpick-dev.example.com.evil.test' })
            ),
        /exact match/
    );
});

test('dev mode rejects userinfo, trailing dots, and unexpected ports', () => {
    assert.throws(
        () =>
            resolveBrowserSmokeConfig(
                remoteEnv({ BASE_URL: 'https://dwarvenpick-dev.example.com@evil.test' })
            ),
        /user information/
    );
    assert.throws(
        () =>
            resolveBrowserSmokeConfig(
                remoteEnv({ BASE_URL: 'https://dwarvenpick-dev.example.com.' })
            ),
        /trailing dot/
    );
    assert.throws(
        () =>
            resolveBrowserSmokeConfig(
                remoteEnv({ BASE_URL: 'https://dwarvenpick-dev.example.com:8443' })
            ),
        /exact match/
    );
});

test('dev mode allows an explicitly approved non-default port', () => {
    const config = resolveBrowserSmokeConfig(
        remoteEnv({
            BASE_URL: 'https://dwarvenpick-dev.example.com:8443',
            BROWSER_SMOKE_ALLOWED_TARGETS: 'dwarvenpick-dev.example.com:8443'
        })
    );

    assert.equal(config.baseUrl, 'https://dwarvenpick-dev.example.com:8443');
});

test('dev mode normalizes host casing and never accepts arbitrary SQL', () => {
    const config = resolveBrowserSmokeConfig(
        remoteEnv({
            BASE_URL: 'https://DWARVENPICK-DEV.EXAMPLE.COM',
            BROWSER_SMOKE_SQL: 'DELETE FROM important_table'
        })
    );

    assert.equal(config.hostname, 'dwarvenpick-dev.example.com');
    assert.equal(config.query, 'SELECT 1 AS browser_smoke;');
});

test('dev mode requires credentials, datasource, and export expectation', () => {
    assert.throws(
        () => resolveBrowserSmokeConfig(remoteEnv({ BROWSER_SMOKE_PASSWORD: '' })),
        /BROWSER_SMOKE_PASSWORD is required/
    );
    assert.throws(
        () => resolveBrowserSmokeConfig(remoteEnv({ BROWSER_SMOKE_DATASOURCE: '' })),
        /BROWSER_SMOKE_DATASOURCE is required/
    );
    assert.throws(
        () => resolveBrowserSmokeConfig(remoteEnv({ BROWSER_SMOKE_EXPECT_EXPORT: 'skip' })),
        /must be 'allowed' or 'denied'/
    );
});

test('safe summary excludes credentials and query text', () => {
    const summary = safeBrowserSmokeTargetSummary(resolveBrowserSmokeConfig(remoteEnv()));
    const serialized = JSON.stringify(summary);

    assert.doesNotMatch(serialized, /approved-user/);
    assert.doesNotMatch(serialized, /never-print-this/);
    assert.doesNotMatch(serialized, /SELECT/);
});
