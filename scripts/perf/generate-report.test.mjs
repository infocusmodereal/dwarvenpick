import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

const generator = new URL("./generate-report.mjs", import.meta.url);
const secretSql = "SELECT fixture_secret FROM private_table";
const secretPassword = "fixture-password-never-report";

function rateMetric(rate, thresholds = {}) {
    return { type: "rate", contains: "default", values: { rate }, thresholds };
}

function counterMetric(count) {
    return {
        type: "counter",
        contains: "default",
        values: { count },
        thresholds: {},
    };
}

function trendMetric(p50 = 10, p95 = 20, p99 = 30, thresholds = {}) {
    return {
        type: "trend",
        contains: "time",
        values: {
            min: 1,
            avg: 12,
            med: p50,
            max: 40,
            "p(90)": 18,
            "p(95)": p95,
            "p(99)": p99,
        },
        thresholds,
    };
}

function summary() {
    return {
        schemaVersion: 1,
        run: { profile: "smoke", vus: 2, duration: "30s" },
        metrics: {
            checks: rateMetric(1, { "rate>0.98": true }),
            http_req_failed: rateMetric(0, { "rate<0.02": true }),
            http_req_duration: trendMetric(5, 15, 25, { "p(95)<2000": true }),
            http_reqs: counterMetric(12),
            dwarvenpick_k6_query_completed: rateMetric(1, {
                "rate>0.95": true,
            }),
            dwarvenpick_k6_query_submit_duration: trendMetric(6, 16, 26),
            dwarvenpick_k6_query_completion_duration: trendMetric(
                200,
                400,
                600,
            ),
            dwarvenpick_k6_result_page_duration: trendMetric(7, 17, 27),
            dwarvenpick_k6_csv_export_duration: trendMetric(8, 18, 28),
            dwarvenpick_k6_result_pages_fetched: counterMetric(6),
        },
    };
}

function prometheus(available = true) {
    if (!available) {
        return {
            schemaVersion: 1,
            available: false,
            namespace: null,
            samples: [],
        };
    }
    const metrics = (offset) => ({
        queuedQueries: offset,
        runningQueries: 2,
        bufferedBytes: 100 + offset,
        bufferedBudgetBytes: 1000,
        poolActive: 2,
        poolTotal: 4,
        queryExecutions: 10 + offset,
        queryDurationSecondsSum: 2 + offset,
        queryDurationSecondsCount: 4 + offset,
        csvExportAttempts: 3 + offset,
        resultBufferRejections: offset,
    });
    return {
        schemaVersion: 1,
        available: true,
        namespace: "dwarvenpick-dev",
        samples: Array.from({ length: 6 }, (_, index) => ({
            capturedAt: `2026-07-10T10:00:${String(index * 2).padStart(2, "0")}.000Z`,
            metrics: metrics(index),
        })),
    };
}

async function runGenerator({
    summaryPayload = summary(),
    prometheusPayload = prometheus(),
} = {}) {
    const directory = await mkdtemp(join(tmpdir(), "dwarvenpick-perf-report-"));
    const summaryPath = join(directory, "summary.json");
    const prometheusPath = join(directory, "prometheus.json");
    const jsonPath = join(directory, "report.json");
    const textPath = join(directory, "report.txt");
    await writeFile(summaryPath, JSON.stringify(summaryPayload));
    await writeFile(prometheusPath, JSON.stringify(prometheusPayload));
    const result = spawnSync(
        process.execPath,
        [generator.pathname, summaryPath, prometheusPath, jsonPath, textPath],
        {
            env: {
                ...process.env,
                PERF_TARGET_ENV: "dev",
                SQL: secretSql,
                DWARVENPICK_PASSWORD: secretPassword,
                EXPORT_CSV: "true",
            },
            encoding: "utf8",
        },
    );
    return {
        result,
        report: JSON.parse(await readFile(jsonPath, "utf8")),
        text: await readFile(textPath, "utf8"),
    };
}

test("reports k6 percentiles and derived Prometheus pressure", async () => {
    const output = await runGenerator();
    assert.equal(output.result.status, 0);
    assert.equal(output.report.k6.http.p50Ms, 5);
    assert.equal(output.report.k6.http.p95Ms, 15);
    assert.equal(output.report.k6.http.p99Ms, 25);
    assert.equal(output.report.prometheus.maxQueuedQueries, 5);
    assert.equal(output.report.prometheus.maxPoolSaturation, 0.5);
    assert.equal(output.report.prometheus.queryExecutions, 5);
    assert.equal(output.report.prometheus.sampleCount, 6);
    assert.equal(output.report.environmentScope, "shared");
    assert.equal(output.report.prometheus.scope, "namespace-wide");
});

test("supports no-Prometheus local mode", async () => {
    const output = await runGenerator({ prometheusPayload: prometheus(false) });
    assert.equal(output.result.status, 0);
    assert.deepEqual(output.report.prometheus, { available: false });
});

test("fails closed when Prometheus captures fewer than six samples", async () => {
    const payload = prometheus();
    payload.samples = payload.samples.slice(0, 5);
    const output = await runGenerator({ prometheusPayload: payload });
    assert.notEqual(output.result.status, 0);
    assert.equal(output.report.status, "incomplete");
    assert.match(output.report.reason, /fewer than 6 samples/);
});

test("fails closed when Prometheus captures no query executions", async () => {
    const payload = prometheus();
    payload.samples.at(-1).metrics.queryExecutions =
        payload.samples[0].metrics.queryExecutions;
    const output = await runGenerator({ prometheusPayload: payload });
    assert.notEqual(output.result.status, 0);
    assert.match(output.report.reason, /no query executions/);
});

test("fails closed when Prometheus captures no CSV export attempts", async () => {
    const payload = prometheus();
    payload.samples.at(-1).metrics.csvExportAttempts =
        payload.samples[0].metrics.csvExportAttempts;
    const output = await runGenerator({ prometheusPayload: payload });
    assert.notEqual(output.result.status, 0);
    assert.match(output.report.reason, /no CSV export attempts/);
});

test("reports the effective k6 profile instead of process defaults", async () => {
    const payload = summary();
    payload.run = { profile: "regression", vus: 10, duration: "2m" };
    const output = await runGenerator({ summaryPayload: payload });
    assert.equal(output.result.status, 0);
    assert.equal(output.report.profile, "regression");
    assert.equal(output.report.vus, 10);
    assert.equal(output.report.duration, "2m");
});

test("preserves threshold failure in the report status", async () => {
    const payload = summary();
    payload.metrics.checks.thresholds["rate>0.98"] = false;
    const output = await runGenerator({ summaryPayload: payload });
    assert.equal(output.result.status, 0);
    assert.equal(output.report.status, "failed");
    assert.equal(output.report.thresholdsPassed, false);
});

test("fails closed when a required metric is absent", async () => {
    const payload = summary();
    delete payload.metrics.http_req_duration;
    const output = await runGenerator({ summaryPayload: payload });
    assert.notEqual(output.result.status, 0);
    assert.equal(output.report.status, "incomplete");
});

test("never writes SQL or password values", async () => {
    const summaryPayload = summary();
    summaryPayload.unexpectedSecret = secretPassword;
    summaryPayload.metrics.http_req_duration.responseBody = secretSql;
    const prometheusPayload = prometheus();
    prometheusPayload.samples[0].unexpectedLabel = secretPassword;
    prometheusPayload.samples[0].metrics.unexpectedSeries = secretSql;
    const output = await runGenerator({ summaryPayload, prometheusPayload });
    const serialized = `${JSON.stringify(output.report)}${output.text}`;
    assert.equal(serialized.includes(secretSql), false);
    assert.equal(serialized.includes(secretPassword), false);
});
