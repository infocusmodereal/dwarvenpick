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
  - `dwarvenpick_query_active_owned{scope="local"}`
  - `dwarvenpick_query_active_heartbeat_oldest_age_seconds`
  - `dwarvenpick_query_active_heartbeat_stale`
  - `dwarvenpick_query_remote_control_pending{action="cancel|kill"}`
  - `dwarvenpick_query_remote_control_oldest_age_seconds{action="cancel|kill"}`
  - `dwarvenpick_query_remote_control_requests_total{action="cancel|kill"}`
  - `dwarvenpick_query_remote_control_latency_seconds{action="cancel|kill"}`
  - `dwarvenpick_query_execution_total{outcome=...}`
  - `dwarvenpick_query_duration_seconds{outcome=...}`
  - `dwarvenpick_query_cancel_total`
  - `dwarvenpick_query_timeout_total`
  - `dwarvenpick_query_buffered_bytes`
  - `dwarvenpick_query_buffered_budget_bytes`
- Exports:
  - `dwarvenpick_query_export_attempts_total{outcome=...}`
- Auth:
  - `dwarvenpick_auth_login_attempts_total{provider=...,outcome=...}`
- Audit persistence:
  - `dwarvenpick_audit_append_total{outcome="success|failure"}`
- Retention cleanup:
  - `dwarvenpick_retention_cleanup_total{scope="query|resource",outcome="success|failure"}`
  - `dwarvenpick_retention_cleanup_last_success_epoch_seconds{scope="query|resource"}`
  - `dwarvenpick_retention_cleanup_last_failure_epoch_seconds{scope="query|resource"}`
  - `dwarvenpick_retention_rows_total{scope=...,store=...,action="pruned|redacted"}`
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
6. Audit persistence failure:
   - Trigger: `increase(dwarvenpick_audit_append_total{outcome="failure"}[5m]) > 0`
7. Retention cleanup failure:
   - Trigger: `increase(dwarvenpick_retention_cleanup_total{outcome="failure"}[1h]) > 0`
8. Stale retention cleanup:
   - Trigger: `time() - max by (namespace, scope) (dwarvenpick_retention_cleanup_last_success_epoch_seconds) > 7200`
9. Active execution heartbeat lag:
   - Trigger: `max by (namespace) (max_over_time(dwarvenpick_query_active_heartbeat_oldest_age_seconds[5m])) > 90`
10. Pending remote query control:
   - Trigger: `max by (namespace, action) (dwarvenpick_query_remote_control_oldest_age_seconds) > 10` for 30s
11. Slow remote query control observation:
   - Trigger: p95 of `dwarvenpick_query_remote_control_latency_seconds` above 5s with at least 3 observations in 15m

Retention cleanup last-success timestamps update after every complete successful run, including runs that affect zero rows. Each backend replica runs cleanup against the shared metadata database; stale checks therefore use the newest successful timestamp for each namespace and scope. Row counters keep the bounded `scope`, `store`, and `action` dimensions and never include actor, datasource, or execution identifiers.

Query lifecycle gauges are read from the shared metadata database by every backend replica. Deduplicate
`dwarvenpick_query_active`, heartbeat, stale, and pending gauges with `max by (namespace, ...)`.
`dwarvenpick_query_active_owned{scope="local"}` is the exception: it is per-pod ownership and must be
summed by namespace. Remote-control `action` is the requested operator intent. Both cancel and kill retain
the existing cooperative statement-cancel and connection-close behavior rather than claiming an
engine-native backend termination.

`dwarvenpick_query_remote_control_latency_seconds` measures shared-database request time to the owning
backend's first observation using database timestamps. The pending-age gauge is the primary liveness
signal because requests whose owner disappears can be recovered by the stale-execution reaper without a
latency observation. The heartbeat alert uses `max_over_time` so the 120-second reaper cannot erase an
observed warning breach before Prometheus evaluates it.
