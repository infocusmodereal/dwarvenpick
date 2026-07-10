#!/usr/bin/env node

import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

const [summaryPath, prometheusPath, jsonOutputPath, textOutputPath] =
    process.argv.slice(2);
if (!summaryPath || !prometheusPath || !jsonOutputPath || !textOutputPath) {
    throw new Error(
        "summary, Prometheus, JSON report, and text report paths are required.",
    );
}

const requiredMetrics = [
    "checks",
    "http_req_failed",
    "http_req_duration",
    "http_reqs",
    "dwarvenpick_k6_query_completed",
    "dwarvenpick_k6_query_submit_duration",
    "dwarvenpick_k6_query_completion_duration",
    "dwarvenpick_k6_result_page_duration",
    "dwarvenpick_k6_result_pages_fetched",
];

function finite(value, label) {
    if (!Number.isFinite(value)) {
        throw new Error(`Required numeric metric is missing: ${label}.`);
    }
    return value;
}

function values(summary, name) {
    const metric = summary.metrics?.[name];
    if (!metric) {
        throw new Error(`Required k6 metric is missing: ${name}.`);
    }
    return metric.values || {};
}

function trend(summary, name) {
    const metric = values(summary, name);
    return {
        p50Ms: finite(metric.med, `${name}.med`),
        p95Ms: finite(metric["p(95)"], `${name}.p(95)`),
        p99Ms: finite(metric["p(99)"], `${name}.p(99)`),
    };
}

function rate(summary, name) {
    return finite(values(summary, name).rate, `${name}.rate`);
}

function count(summary, name) {
    return finite(values(summary, name).count, `${name}.count`);
}

function thresholdsPassed(summary) {
    return Object.values(summary.metrics || {}).every((metric) =>
        Object.values(metric.thresholds || {}).every((ok) => ok === true),
    );
}

function maxMetric(samples, name) {
    return Math.max(
        ...samples.map((sample) =>
            finite(sample.metrics?.[name], `prometheus.${name}`),
        ),
    );
}

function ratio(numerator, denominator) {
    return denominator > 0 ? numerator / denominator : 0;
}

function delta(samples, name) {
    if (samples.length < 2) {
        return 0;
    }
    const first = finite(
        samples[0].metrics?.[name],
        `prometheus.${name}.first`,
    );
    const last = finite(
        samples.at(-1).metrics?.[name],
        `prometheus.${name}.last`,
    );
    return Math.max(0, last - first);
}

function analyzePrometheus(prometheus, requireCsvExport) {
    if (!prometheus.available) {
        return { available: false };
    }
    if (!Array.isArray(prometheus.samples) || prometheus.samples.length < 2) {
        throw new Error(
            "Prometheus was configured but fewer than two samples were captured.",
        );
    }
    const samples = prometheus.samples;
    const elapsedSeconds =
        samples.length > 1
            ? Math.max(
                  0.001,
                  (Date.parse(samples.at(-1).capturedAt) -
                      Date.parse(samples[0].capturedAt)) /
                      1000,
              )
            : null;
    const durationCount = delta(samples, "queryDurationSecondsCount");
    const durationSum = delta(samples, "queryDurationSecondsSum");
    const queryExecutions = delta(samples, "queryExecutions");
    const csvExportAttempts = delta(samples, "csvExportAttempts");
    if (queryExecutions <= 0) {
        throw new Error("Prometheus captured no query executions during the smoke.");
    }
    if (requireCsvExport && csvExportAttempts <= 0) {
        throw new Error("Prometheus captured no CSV export attempts during the smoke.");
    }
    return {
        available: true,
        namespace: prometheus.namespace,
        sampleCount: samples.length,
        maxQueuedQueries: maxMetric(samples, "queuedQueries"),
        maxRunningQueries: maxMetric(samples, "runningQueries"),
        maxResultBufferPressure: Math.max(
            ...samples.map((sample) =>
                ratio(
                    sample.metrics.bufferedBytes,
                    sample.metrics.bufferedBudgetBytes,
                ),
            ),
        ),
        maxPoolSaturation: Math.max(
            ...samples.map((sample) =>
                ratio(sample.metrics.poolActive, sample.metrics.poolTotal),
            ),
        ),
        queryExecutions,
        queryExecutionsPerSecond:
            elapsedSeconds === null ? null : queryExecutions / elapsedSeconds,
        backendAverageDurationMs:
            durationCount > 0 ? (durationSum / durationCount) * 1000 : null,
        csvExportAttempts,
        resultBufferRejections: delta(samples, "resultBufferRejections"),
    };
}

async function writeIncomplete(message) {
    const report = {
        schemaVersion: 1,
        status: "incomplete",
        targetEnvironment: process.env.PERF_TARGET_ENV || "unknown",
        reason: message,
    };
    await mkdir(dirname(jsonOutputPath), { recursive: true });
    await writeFile(jsonOutputPath, `${JSON.stringify(report, null, 2)}\n`);
    await writeFile(
        textOutputPath,
        `Dwarvenpick query performance report\nstatus=incomplete\nreason=${message}\n`,
    );
}

try {
    const summary = JSON.parse(await readFile(summaryPath, "utf8"));
    const prometheus = JSON.parse(await readFile(prometheusPath, "utf8"));
    if (summary.schemaVersion !== 1 || prometheus.schemaVersion !== 1) {
        throw new Error("Unsupported performance artifact schema version.");
    }
    const run = summary.run;
    if (
        !run ||
        !["smoke", "regression"].includes(run.profile) ||
        !Number.isInteger(run.vus) ||
        run.vus < 1 ||
        typeof run.duration !== "string" ||
        !run.duration
    ) {
        throw new Error("Required k6 run metadata is missing or invalid.");
    }
    requiredMetrics.forEach((name) => values(summary, name));
    const exportEnabled =
        (process.env.EXPORT_CSV || "true").toLowerCase() !== "false";
    if (exportEnabled) {
        values(summary, "dwarvenpick_k6_csv_export_duration");
    }
    const queryMix = (process.env.SQL_MIX || process.env.SQL || "")
        .split(/\n---+\n/)
        .map((sql) => sql.trim())
        .filter(Boolean);
    const report = {
        schemaVersion: 1,
        status: thresholdsPassed(summary) ? "passed" : "failed",
        targetEnvironment: process.env.PERF_TARGET_ENV,
        profile: run.profile,
        vus: run.vus,
        duration: run.duration,
        queryCount: queryMix.length,
        thresholdsPassed: thresholdsPassed(summary),
        k6: {
            checksRate: rate(summary, "checks"),
            httpFailureRate: rate(summary, "http_req_failed"),
            httpRequests: count(summary, "http_reqs"),
            http: trend(summary, "http_req_duration"),
            queryCompletionRate: rate(
                summary,
                "dwarvenpick_k6_query_completed",
            ),
            querySubmit: trend(summary, "dwarvenpick_k6_query_submit_duration"),
            queryCompletion: trend(
                summary,
                "dwarvenpick_k6_query_completion_duration",
            ),
            resultPage: trend(summary, "dwarvenpick_k6_result_page_duration"),
            csvExport: exportEnabled
                ? trend(summary, "dwarvenpick_k6_csv_export_duration")
                : null,
            resultPagesFetched: count(
                summary,
                "dwarvenpick_k6_result_pages_fetched",
            ),
        },
        prometheus: analyzePrometheus(prometheus, exportEnabled),
    };

    await mkdir(dirname(jsonOutputPath), { recursive: true });
    await writeFile(jsonOutputPath, `${JSON.stringify(report, null, 2)}\n`);
    const lines = [
        "Dwarvenpick query performance report",
        `status=${report.status}`,
        `target_environment=${report.targetEnvironment}`,
        `profile=${report.profile}`,
        `vus=${report.vus}`,
        `duration=${report.duration}`,
        `query_count=${report.queryCount}`,
        `http_p50_ms=${report.k6.http.p50Ms}`,
        `http_p95_ms=${report.k6.http.p95Ms}`,
        `http_p99_ms=${report.k6.http.p99Ms}`,
        `http_failure_rate=${report.k6.httpFailureRate}`,
        `query_completion_p95_ms=${report.k6.queryCompletion.p95Ms}`,
        `query_completion_p99_ms=${report.k6.queryCompletion.p99Ms}`,
        `result_page_p95_ms=${report.k6.resultPage.p95Ms}`,
        `prometheus_available=${report.prometheus.available}`,
    ];
    if (report.prometheus.available) {
        lines.push(
            `max_queued_queries=${report.prometheus.maxQueuedQueries}`,
            `max_result_buffer_pressure=${report.prometheus.maxResultBufferPressure}`,
            `max_pool_saturation=${report.prometheus.maxPoolSaturation}`,
            `backend_average_duration_ms=${report.prometheus.backendAverageDurationMs}`,
        );
    }
    await writeFile(textOutputPath, `${lines.join("\n")}\n`);
} catch (error) {
    const message =
        error instanceof Error ? error.message : "Unknown report error.";
    await writeIncomplete(message.replace(/[\r\n]/g, " "));
    process.stderr.write(`Performance report generation failed: ${message}\n`);
    process.exitCode = 1;
}
