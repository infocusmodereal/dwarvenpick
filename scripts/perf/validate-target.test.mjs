import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import test from "node:test";

const script = new URL("./validate-target.mjs", import.meta.url);
const inheritedEnv = { ...process.env };
for (const name of [
    "PERF_TARGET_ENV",
    "PERF_ALLOWED_HOSTS",
    "BASE_URL",
    "PROMETHEUS_URL",
    "PROMETHEUS_NAMESPACE",
]) {
    delete inheritedEnv[name];
}
const baseEnv = {
    ...inheritedEnv,
    DWARVENPICK_USER: "fixture-user",
    DWARVENPICK_PASSWORD: "fixture-password",
    DATASOURCE_ID: "fixture-datasource",
    SQL: "fixture-sensitive-sql",
};

function run(extraEnv) {
    return spawnSync(process.execPath, [script.pathname], {
        env: { ...baseEnv, ...extraEnv },
        encoding: "utf8",
    });
}

test("allows loopback local target", () => {
    assert.equal(
        run({ PERF_TARGET_ENV: "local", BASE_URL: "http://127.0.0.1:8080" })
            .status,
        0,
    );
});

test("requires exact dev allowlist membership", () => {
    const result = run({
        PERF_TARGET_ENV: "dev",
        BASE_URL: "https://dwarvenpick-dev.indexexchange.com",
        PERF_ALLOWED_HOSTS: "dwarvenpick-dev.indexexchange.com",
        PROMETHEUS_URL: "https://prometheus.example.test",
        PROMETHEUS_NAMESPACE: "dwarvenpick-dev",
    });
    assert.equal(result.status, 0);
});

test("rejects canonical production target even when allowlisted", () => {
    const result = run({
        PERF_TARGET_ENV: "dev",
        BASE_URL: "https://dwarvenpick.indexexchange.com",
        PERF_ALLOWED_HOSTS: "dwarvenpick.indexexchange.com",
    });
    assert.notEqual(result.status, 0);
});

test("rejects unlisted aliases and malformed namespaces", () => {
    assert.notEqual(
        run({
            PERF_TARGET_ENV: "dev",
            BASE_URL: "https://production-alias.example",
            PERF_ALLOWED_HOSTS: "dwarvenpick-dev.indexexchange.com",
        }).status,
        0,
    );
    assert.notEqual(
        run({
            PERF_TARGET_ENV: "dev",
            BASE_URL: "https://dwarvenpick-dev.indexexchange.com",
            PERF_ALLOWED_HOSTS: "dwarvenpick-dev.indexexchange.com",
            PROMETHEUS_NAMESPACE: 'bad"matcher',
        }).status,
        0,
    );
});

test("rejects partial Prometheus configuration before starting k6", () => {
    const result = run({
        PERF_TARGET_ENV: "local",
        BASE_URL: "http://127.0.0.1:8080",
        PROMETHEUS_URL: "https://prometheus.example.test",
    });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /must be configured together/);
});

test("does not echo credentials or SQL on failure", () => {
    const result = run({
        PERF_TARGET_ENV: "prod",
        BASE_URL: "https://example.test",
    });
    const output = `${result.stdout}${result.stderr}`;
    assert.equal(output.includes("fixture-password"), false);
    assert.equal(output.includes("fixture-sensitive-sql"), false);
});
