---
title: Performance testing
parent: Development
nav_order: 20
---

# Performance testing

This repository includes a repeatable load harness for concurrent query execution, pagination, and CSV export.

## k6 workload script

- Script: `scripts/perf/query-load.k6.js`
- Artifact wrapper: `scripts/perf/run-query-smoke.sh`
- Target behavior:
  - login
  - submit query
  - poll status
  - fetch paginated result pages
  - trigger CSV export
  - capture backend query, queue, pool, export, and result-buffer metrics when `/actuator/prometheus` is reachable

## CI-safe smoke

```bash
BASE_URL=http://localhost:3000 \
DWARVENPICK_AUTH=local \
DWARVENPICK_USER=admin \
DWARVENPICK_PASSWORD=Admin1234! \
DATASOURCE_ID=postgresql-core \
SQL='SELECT 1 AS value;' \
scripts/perf/run-query-smoke.sh
```

The smoke profile defaults to 2 VUs for 30 seconds and writes:

- `build/reports/perf/query-smoke-summary.json`
- `build/reports/perf/prometheus-before.prom`
- `build/reports/perf/prometheus-after.prom`
- `build/reports/perf/query-smoke-report.txt`

Default smoke thresholds:

- k6 checks rate > 98%
- HTTP failed rate < 2%
- HTTP request p95 < 2s and p99 < 5s
- completed query rate > 95%
- result page p95 < 1.5s
- CSV export p95 < 5s

## Regression profile

```bash
PERF_PROFILE=regression \
VUS=10 \
DURATION=2m \
BASE_URL=http://localhost:3000 \
DWARVENPICK_AUTH=local \
DWARVENPICK_USER=admin \
DWARVENPICK_PASSWORD=Admin1234! \
DATASOURCE_ID=postgresql-core \
SQL='SELECT 1 AS value;' \
scripts/perf/run-query-smoke.sh
```

The regression profile keeps the same flow but loosens latency thresholds for sustained concurrency:

- k6 checks rate > 97%
- HTTP failed rate < 3%
- HTTP request p95 < 3s and p99 < 8s
- completed query rate > 95%
- result page p95 < 2s
- CSV export p95 < 8s

## Direct k6 run

```bash
k6 run \
  -e PERF_PROFILE=smoke \
  -e BASE_URL=http://localhost:3000 \
  -e DWARVENPICK_AUTH=local \
  -e DWARVENPICK_USER=admin \
  -e DWARVENPICK_PASSWORD=Admin1234! \
  -e DATASOURCE_ID=postgresql-core \
  -e SQL='SELECT 1 AS value;' \
  -e PAGE_SIZE=100 \
  -e VUS=10 \
  -e DURATION=2m \
  scripts/perf/query-load.k6.js
```

Use `DWARVENPICK_AUTH=ldap` for LDAP-backed environments. For governed non-read-only profiles, pass
`CREDENTIAL_PROFILE` and `JUSTIFICATION`; the harness forwards the same CSRF/session context used for login into query
submission. Use `CAPTURE_APP_METRICS=false` when the target intentionally blocks `/actuator/prometheus` from the client
path.

Separate representative query mixes with a line containing only dashes:

```bash
SQL_MIX=$'SELECT 1 AS value;\n---\nSELECT generate_series(1, 25);' \
k6 run scripts/perf/query-load.k6.js
```

## Output to capture

- `http_req_duration` p50/p95/p99
- `http_req_failed` error rate
- request rate (`http_reqs`)
- `dwarvenpick_k6_query_completed`
- `dwarvenpick_k6_result_page_duration`
- `dwarvenpick_k6_csv_export_duration`
- `dwarvenpick_k6_result_pages_fetched`
- `dwarvenpick_k6_csv_exports_attempted`
- backend metrics from `/actuator/prometheus` during the run:
  - `dwarvenpick_query_active`
  - `dwarvenpick_query_execution_total`
  - `dwarvenpick_query_duration_seconds`
  - `dwarvenpick_query_export_attempts_total`
  - `dwarvenpick_query_buffered_bytes`
  - `dwarvenpick_query_buffered_budget_bytes`
  - `dwarvenpick_pool_active`
  - `dwarvenpick_pool_total`

## Manual gate expectations

- Query concurrency stays within the configured execution limits; unexpected growth in
  `dwarvenpick_query_active{status="queued"}` is treated as queue pressure.
- Result paging and CSV export checks pass or CSV export returns 403 for profiles without export access.
- Result-buffer pressure remains below alert thresholds:
  `dwarvenpick_query_buffered_bytes / dwarvenpick_query_buffered_budget_bytes`.
- Pool saturation remains below alert thresholds:
  `dwarvenpick_pool_active / dwarvenpick_pool_total`.
- The report includes sanitized timings only. Do not store query text containing sensitive predicates or result payloads.

## Reporting template

- Environment: local/dev/stage
- Workload: VUs + duration + query mix
- Latency: p50/p95/p99
- Error rate: %
- Notable bottlenecks and suggested mitigations
