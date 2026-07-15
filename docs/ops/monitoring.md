---
title: Monitoring and alerts
parent: Operations
nav_order: 30
---

# Monitoring and alerts

## Metrics endpoint

- Prometheus metrics: `GET /actuator/prometheus`
- Health probes:
  - `GET /actuator/health/liveness`
  - `GET /actuator/health/readiness`

## Configuration

- `DWARVENPICK_METRICS_PROMETHEUS_ENABLED` (default: `true`) enables the Prometheus endpoint.
- Helm chart value: `metrics.prometheus.enabled`

## Kubernetes scraping

The Helm chart exposes the backend on `.Values.service.port` (default `8080`). Configure your Prometheus instance to scrape:

- `http://<service-name>:8080/actuator/prometheus`

If you use Prometheus Operator, create a `ServiceMonitor` that targets the backend `Service` and port.

## Key metrics

- Build and scrape identity:
  - `dwarvenpick_build_info{service=...,version=...,source_ref=...,source_sha=...,image_tag=...,build_tag=...}`
  - The constant value is `1`; use the Prometheus sample timestamp for freshness checks.
- Query lifecycle:
  - `dwarvenpick_query_active{status="queued|running"}`
  - `dwarvenpick_query_execution_total{outcome=...}`
  - `dwarvenpick_query_duration_seconds{outcome=...}`
  - `dwarvenpick_query_cancel_total`
  - `dwarvenpick_query_timeout_total`
  - `dwarvenpick_query_buffered_bytes`
  - `dwarvenpick_query_buffered_budget_bytes`
- Exports:
  - `dwarvenpick_query_export_attempts_total{outcome=...}`
- Persisted results (database-wide; use `max` across backend pods):
  - `dwarvenpick_query_persisted_result_bytes`
  - `dwarvenpick_query_persisted_result_pages`
  - `dwarvenpick_query_persisted_result_expiry_candidates`
  - `dwarvenpick_query_persisted_result_expiry_lag_seconds`
  - `dwarvenpick_query_persisted_result_export_leases`
  - `dwarvenpick_query_persisted_result_cleanup_failures_total`
- Auth:
  - `dwarvenpick_auth_login_attempts_total{provider=...,outcome=...}`
- Pools:
  - `dwarvenpick_pool_active`
  - `dwarvenpick_pool_idle`
  - `dwarvenpick_pool_total`

Keep `/actuator/prometheus` enabled for internal Prometheus scraping. Public exposure must be restricted at the ingress or network boundary; disabling the endpoint also removes `dwarvenpick_build_info` and all operational metrics.

## Recommended alerts

1. High query failure rate:
   - Trigger: `increase(dwarvenpick_query_execution_total{outcome="failed"}[5m]) / increase(dwarvenpick_query_execution_total[5m]) > 0.2`
2. Query timeout burst:
   - Trigger: `increase(dwarvenpick_query_timeout_total[5m]) > 10`
3. High queue pressure:
   - Trigger: `dwarvenpick_query_active{status="queued"} > 20` for 10m
4. Pool saturation:
   - Trigger: `dwarvenpick_pool_active / dwarvenpick_pool_total > 0.9` for 5m
5. Login failure surge:
   - Trigger: `increase(dwarvenpick_auth_login_attempts_total{outcome="failed"}[5m]) > 25`
6. Persisted result cleanup failure:
   - Trigger: `increase(dwarvenpick_query_persisted_result_cleanup_failures_total[10m]) > 0`
7. Persisted result cleanup lag:
   - Trigger: `max(dwarvenpick_query_persisted_result_expiry_lag_seconds) > 900` for 10m after deletion is enabled

Alert separately on provider-reported metadata database free bytes using the reviewed storage envelope in [Persisted result lifecycle](persisted-result-lifecycle.md).
