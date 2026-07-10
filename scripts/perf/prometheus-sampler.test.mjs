import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, readFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

const sampler = new URL("./prometheus-sampler.mjs", import.meta.url);

async function waitForFile(path) {
    for (let attempt = 0; attempt < 100; attempt += 1) {
        try {
            return JSON.parse(await readFile(path, "utf8"));
        } catch {
            await new Promise((resolve) => setTimeout(resolve, 25));
        }
    }
    throw new Error("Sampler output was not written.");
}

test("writes no-Prometheus marker without starting a sampler loop", async () => {
    const directory = await mkdtemp(join(tmpdir(), "dwarvenpick-prom-none-"));
    const output = join(directory, "prometheus.json");
    const child = spawn(process.execPath, [sampler.pathname, output], {
        env: { ...process.env },
    });
    const exitCode = await new Promise((resolve) => child.on("exit", resolve));
    assert.equal(exitCode, 0);
    assert.deepEqual(await waitForFile(output), {
        schemaVersion: 1,
        available: false,
        namespace: null,
        samples: [],
    });
});

test("stores only fixed scalar keys and no PromQL or response labels", async () => {
    const server = createServer((request, response) => {
        const query = new URL(request.url, "http://localhost").searchParams.get(
            "query",
        );
        assert.equal(query.includes("dwarvenpick-dev"), true);
        response.setHeader("Content-Type", "application/json");
        response.end(
            JSON.stringify({
                status: "success",
                data: {
                    result: [
                        {
                            metric: {
                                instance: "private-host.internal:8080",
                                pod: "private-pod",
                            },
                            value: [Date.now() / 1000, "1"],
                        },
                    ],
                },
            }),
        );
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    const directory = await mkdtemp(join(tmpdir(), "dwarvenpick-prom-sample-"));
    const output = join(directory, "prometheus.json");
    const child = spawn(process.execPath, [sampler.pathname, output], {
        env: {
            ...process.env,
            PROMETHEUS_URL: `http://127.0.0.1:${address.port}`,
            PROMETHEUS_NAMESPACE: "dwarvenpick-dev",
            PROMETHEUS_SAMPLE_INTERVAL_MS: "500",
        },
    });
    const payload = await waitForFile(output);
    child.kill("SIGTERM");
    await new Promise((resolve) => child.on("exit", resolve));
    server.close();

    assert.equal(payload.available, true);
    assert.equal(payload.samples.length >= 1, true);
    assert.deepEqual(Object.keys(payload.samples[0].metrics).sort(), [
        "bufferedBudgetBytes",
        "bufferedBytes",
        "csvExportAttempts",
        "poolActive",
        "poolTotal",
        "queryDurationSecondsCount",
        "queryDurationSecondsSum",
        "queryExecutions",
        "queuedQueries",
        "resultBufferRejections",
        "runningQueries",
    ]);
    const serialized = JSON.stringify(payload);
    assert.equal(serialized.includes("private-host"), false);
    assert.equal(serialized.includes("private-pod"), false);
    assert.equal(serialized.includes("sum("), false);
});
