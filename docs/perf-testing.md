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

The manual `Manual Query Performance Smoke` GitHub Actions workflow requires a semantic release tag, builds a minimal
PostgreSQL and backend stack from that tag, and uploads a sanitized report artifact. It uses only seeded local
credentials and never targets dev or prod.

Run the same profile locally with Docker Compose:

```bash
docker compose -f deploy/perf/docker-compose.yml up --build -d

PERF_TARGET_ENV=local \
BASE_URL=http://127.0.0.1:8080 \
DWARVENPICK_AUTH=local \
DWARVENPICK_USER=admin \
DWARVENPICK_PASSWORD=Admin1234! \
DATASOURCE_ID=postgresql-core \
SQL='SELECT generate_series(1, 250) AS value;' \
PAGE_SIZE=100 \
MAX_RESULT_PAGES=3 \
CAPTURE_APP_METRICS=true \
scripts/perf/run-query-smoke.sh

docker compose -f deploy/perf/docker-compose.yml down -v
```

Set `PERF_BACKEND_PORT` and `PERF_PROMETHEUS_PORT` before both Compose and smoke
commands when `8080` or `9090` is already in use.

The smoke profile defaults to 2 VUs for 30 seconds. The workflow uses 1 VU for 15 seconds to keep the manual CI check
small. Both write:

- `build/reports/perf/query-smoke-summary.json`
- `build/reports/perf/prometheus-samples.json`
- `build/reports/perf/query-smoke-report.json`
- `build/reports/perf/query-smoke-report.txt`

The generated reports contain only aggregate timings, counters, ratios, threshold results, the validated namespace,
and an allowlisted release identity (`version`, `sourceRef`, `sourceSha`, `imageTag`, and `buildTag`). The wrapper reads
the public `/api/version` response through a temporary file that is deleted after report generation. Set
`EXPECTED_SOURCE_REF`, `EXPECTED_SOURCE_SHA`, `EXPECTED_IMAGE_TAG`, or `EXPECTED_BUILD_TAG` to make identity drift fail
the run.
They exclude credentials, cookies, query text, result rows, PromQL, and source-series labels such as pod or instance.
The wrapper disables k6 HTTP debug output and external log sinks, and the Prometheus sampler does not inherit query or
application credential environment variables.

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
PERF_TARGET_ENV=local \
BASE_URL=http://127.0.0.1:8080 \
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

## Dev smoke

```bash
PERF_TARGET_ENV=dev \
PERF_ALLOWED_HOSTS=dwarvenpick-dev.indexexchange.com \
BASE_URL=https://dwarvenpick-dev.indexexchange.com \
DWARVENPICK_AUTH=ldap \
DWARVENPICK_USER="$DWARVENPICK_DEV_USER" \
DWARVENPICK_PASSWORD="$DWARVENPICK_DEV_PASSWORD" \
DATASOURCE_ID=starrocks-dev-adhoc \
CREDENTIAL_PROFILE=read-only \
SQL='SELECT 1 AS value;' \
CAPTURE_APP_METRICS=false \
PROMETHEUS_URL=https://pandora-internal-prometheus.indexexchange.com \
PROMETHEUS_NAMESPACE=dwarvenpick-dev \
scripts/perf/run-query-smoke.sh
```

The wrapper accepts only loopback URLs for `PERF_TARGET_ENV=local` and exact `PERF_ALLOWED_HOSTS` membership for
`PERF_TARGET_ENV=dev`. The canonical production hostname is always rejected. For governed non-read-only profiles, pass
`CREDENTIAL_PROFILE` and `JUSTIFICATION`; the harness forwards the same CSRF/session context used for login into query
submission. Use `CAPTURE_APP_METRICS=false` when the target intentionally blocks `/actuator/prometheus` from the client
path.

Separate representative query mixes with a line containing only dashes:

```bash
SQL_MIX=$'SELECT 1 AS value;\n---\nSELECT generate_series(1, 25);' \
PERF_TARGET_ENV=local \
BASE_URL=http://127.0.0.1:8080 \
scripts/perf/run-query-smoke.sh
```

## Output to capture

- `http_req_duration` p50/p95/p99
- `dwarvenpick_k6_query_submit_duration` p50/p95/p99
- `dwarvenpick_k6_query_completion_duration` p50/p95/p99
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
    - `dwarvenpick_query_result_storage_used_bytes`
    - `dwarvenpick_query_result_storage_budget_bytes`
    - `dwarvenpick_query_result_storage_headroom_bytes`
    - `dwarvenpick_query_result_storage_table_bytes`
    - `dwarvenpick_query_result_storage_rejections_total`
    - `dwarvenpick_query_result_storage_persistence_seconds`
    - `dwarvenpick_query_result_storage_cleanup_seconds`
    - `dwarvenpick_pool_active`
    - `dwarvenpick_pool_total`, with `dwarvenpick_pool` as the compatibility fallback

## Manual gate expectations

- Query concurrency stays within the configured execution limits; unexpected growth in
  `dwarvenpick_query_active{status="queued"}` is treated as queue pressure.
- Result paging and CSV export checks pass or CSV export returns 403 for profiles without export access.
- Result-buffer pressure remains below alert thresholds:
  `dwarvenpick_query_buffered_bytes / dwarvenpick_query_buffered_budget_bytes`.
- Persisted result storage stays below 80% of its fixed budget, and any budget rejection is accompanied by a successful
  query with an explicitly truncated result while history and status endpoints remain responsive.
- Pool saturation remains below alert thresholds:
  `dwarvenpick_pool_active / dwarvenpick_pool_total`.
- A run without Prometheus access remains valid for local functional timings, but the report marks backend metrics as
  unavailable. Rollout evidence requires the Prometheus snapshot.
- Prometheus-backed evidence requires at least six samples. Dev pressure metrics are namespace-wide observations from a
  shared environment; record whether the window was otherwise quiet and do not attribute unrelated ambient load to the
  smoke.
- Keep reports under `build/reports/perf`, which is gitignored. Never upload raw Prometheus responses, Compose logs,
  query text containing sensitive predicates, or result payloads.

## Reporting template

- Environment: local/dev
- Workload: VUs + duration + query mix
- Latency: p50/p95/p99
- Error rate: %
- Pressure: max queued/running queries, buffer ratio, and pool ratio
- Counters: completed executions, exports, and result-buffer rejections
- Notable bottlenecks and suggested mitigations
