#!/usr/bin/env node

const required = [
    "BASE_URL",
    "DWARVENPICK_USER",
    "DWARVENPICK_PASSWORD",
    "DATASOURCE_ID",
];
for (const name of required) {
    if (!process.env[name]?.trim()) {
        throw new Error(`${name} is required.`);
    }
}
if (!process.env.SQL?.trim() && !process.env.SQL_MIX?.trim()) {
    throw new Error("SQL or SQL_MIX is required.");
}

const targetEnvironment = process.env.PERF_TARGET_ENV;
if (!["local", "dev"].includes(targetEnvironment)) {
    throw new Error(
        "PERF_TARGET_ENV must be local or dev. Production performance runs are not supported.",
    );
}

let target;
try {
    target = new URL(process.env.BASE_URL);
} catch {
    throw new Error("BASE_URL must be a valid URL.");
}
if (!["http:", "https:"].includes(target.protocol)) {
    throw new Error("BASE_URL must use HTTP or HTTPS.");
}

const hostname = target.hostname.toLowerCase();
if (hostname === "dwarvenpick.indexexchange.com") {
    throw new Error(
        "Production Dwarvenpick is never an allowed performance target.",
    );
}

if (targetEnvironment === "local") {
    const localHosts = new Set(["localhost", "127.0.0.1", "[::1]", "::1"]);
    if (!localHosts.has(hostname)) {
        throw new Error("Local performance runs require a loopback BASE_URL.");
    }
} else {
    const allowedHosts = new Set(
        (process.env.PERF_ALLOWED_HOSTS || "")
            .split(",")
            .map((value) => value.trim().toLowerCase())
            .filter(Boolean),
    );
    if (allowedHosts.size === 0 || !allowedHosts.has(hostname)) {
        throw new Error(
            "Dev BASE_URL hostname must be explicitly listed in PERF_ALLOWED_HOSTS.",
        );
    }
}

const prometheusUrl = process.env.PROMETHEUS_URL?.trim();
const prometheusNamespace = process.env.PROMETHEUS_NAMESPACE?.trim();
if (
    (prometheusUrl && !prometheusNamespace) ||
    (!prometheusUrl && prometheusNamespace)
) {
    throw new Error(
        "PROMETHEUS_URL and PROMETHEUS_NAMESPACE must be configured together.",
    );
}
if (prometheusNamespace && !/^[A-Za-z0-9_-]+$/.test(prometheusNamespace)) {
    throw new Error("PROMETHEUS_NAMESPACE has invalid characters.");
}

process.stdout.write(
    `Performance target validated for ${targetEnvironment}.\n`,
);
