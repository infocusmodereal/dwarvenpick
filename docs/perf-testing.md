---
title: Performance testing
parent: Development
nav_order: 20
---

# Performance testing

This repository includes a repeatable load harness for concurrent query execution, pagination, and CSV export.

## k6 workload script

- Script: `scripts/perf/query-load.k6.js`
- Target behavior:
  - login
  - submit query
  - poll status
  - fetch result page
  - trigger CSV export

## Run

```bash
k6 run \
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
submission.

Separate representative query mixes with a line containing only dashes:

```bash
SQL_MIX=$'SELECT 1 AS value;\n---\nSELECT generate_series(1, 25);' \
k6 run scripts/perf/query-load.k6.js
```

## Output to capture

- `http_req_duration` p50/p95/p99
- `http_req_failed` error rate
- request rate (`http_reqs`)
- backend metrics from `/actuator/prometheus` during the run:
  - `dwarvenpick_query_active`
  - `dwarvenpick_query_execution_total`
  - `dwarvenpick_query_duration_seconds`
  - `dwarvenpick_query_export_attempts_total`
  - `dwarvenpick_pool_active`

## Reporting template

- Environment: local/dev/stage
- Workload: VUs + duration + query mix
- Latency: p50/p95/p99
- Error rate: %
- Notable bottlenecks and suggested mitigations
