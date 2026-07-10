#!/usr/bin/env node

import { mkdir, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

const outputPath = process.argv[2];
if (!outputPath) {
    throw new Error("Prometheus sampler output path is required.");
}

const prometheusUrl = process.env.PROMETHEUS_URL?.trim();
const namespace = process.env.PROMETHEUS_NAMESPACE?.trim();
const intervalMs = Number(process.env.PROMETHEUS_SAMPLE_INTERVAL_MS || 2000);
if ((prometheusUrl && !namespace) || (!prometheusUrl && namespace)) {
    throw new Error(
        "PROMETHEUS_URL and PROMETHEUS_NAMESPACE must be configured together.",
    );
}
if (namespace && !/^[A-Za-z0-9_-]+$/.test(namespace)) {
    throw new Error("PROMETHEUS_NAMESPACE has invalid characters.");
}
if (!Number.isFinite(intervalMs) || intervalMs < 500 || intervalMs > 60000) {
    throw new Error(
        "PROMETHEUS_SAMPLE_INTERVAL_MS must be between 500 and 60000.",
    );
}

const snapshot = {
    schemaVersion: 1,
    available: Boolean(prometheusUrl),
    namespace: namespace || null,
    samples: [],
};

async function persist() {
    await mkdir(dirname(outputPath), { recursive: true });
    const temporaryPath = `${outputPath}.tmp`;
    await writeFile(temporaryPath, `${JSON.stringify(snapshot, null, 2)}\n`, {
        mode: 0o600,
    });
    await rename(temporaryPath, outputPath);
}

if (!prometheusUrl) {
    await persist();
    process.exit(0);
}

let endpoint;
try {
    endpoint = new URL("/api/v1/query", prometheusUrl);
} catch {
    throw new Error("PROMETHEUS_URL must be a valid URL.");
}
if (!["http:", "https:"].includes(endpoint.protocol)) {
    throw new Error("PROMETHEUS_URL must use HTTP or HTTPS.");
}

const selector = `{namespace="${namespace}"}`;
const queries = {
    queuedQueries: `sum(dwarvenpick_query_active${selector.replace("}", ',status="queued"}')}) or vector(0)`,
    runningQueries: `sum(dwarvenpick_query_active${selector.replace("}", ',status="running"}')}) or vector(0)`,
    bufferedBytes: `sum(dwarvenpick_query_buffered_bytes${selector}) or vector(0)`,
    bufferedBudgetBytes: `sum(dwarvenpick_query_buffered_budget_bytes${selector}) or vector(0)`,
    poolActive: `sum(dwarvenpick_pool_active${selector}) or vector(0)`,
    poolTotal: `sum(dwarvenpick_pool_total${selector}) or sum(dwarvenpick_pool${selector}) or vector(0)`,
    queryExecutions: `sum(dwarvenpick_query_execution_total${selector}) or vector(0)`,
    queryDurationSecondsSum: `sum(dwarvenpick_query_duration_seconds_sum${selector}) or vector(0)`,
    queryDurationSecondsCount: `sum(dwarvenpick_query_duration_seconds_count${selector}) or vector(0)`,
    csvExportAttempts: `sum(dwarvenpick_query_export_attempts_total${selector}) or vector(0)`,
    resultBufferRejections: `sum(dwarvenpick_query_result_buffer_rejections_total${selector}) or vector(0)`,
};

async function queryScalar(query) {
    const url = new URL(endpoint);
    url.searchParams.set("query", query);
    const response = await fetch(url, { signal: AbortSignal.timeout(10000) });
    if (!response.ok) {
        throw new Error(
            `Prometheus query failed with HTTP ${response.status}.`,
        );
    }
    const payload = await response.json();
    const results = payload?.data?.result;
    if (
        payload.status !== "success" ||
        !Array.isArray(results) ||
        results.length !== 1
    ) {
        throw new Error(
            "Prometheus scalar query returned an unexpected result.",
        );
    }
    const value = Number(results[0]?.value?.[1]);
    if (!Number.isFinite(value)) {
        throw new Error("Prometheus scalar query returned a nonnumeric value.");
    }
    return value;
}

async function sample() {
    const metrics = {};
    for (const [name, query] of Object.entries(queries)) {
        metrics[name] = await queryScalar(query);
    }
    snapshot.samples.push({ capturedAt: new Date().toISOString(), metrics });
    await persist();
}

let stopping = false;
process.on("SIGTERM", () => {
    stopping = true;
});
process.on("SIGINT", () => {
    stopping = true;
});

await sample();
while (!stopping) {
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
    if (!stopping) {
        await sample();
    }
}
