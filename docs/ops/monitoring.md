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

- Query lifecycle:
  - `dwarvenpick_query_active{status="queued|running"}`
  - `dwarvenpick_query_execution_total{outcome=...}`
  - `dwarvenpick_query_duration_seconds{outcome=...}`
  - `dwarvenpick_query_cancel_total`
  - `dwarvenpick_query_timeout_total`
- Exports:
  - `dwarvenpick_query_export_attempts_total{outcome=...}`
- Auth:
  - `dwarvenpick_auth_login_attempts_total{provider=...,outcome=...}`
- Pools:
  - `dwarvenpick_pool_active`
  - `dwarvenpick_pool_idle`
  - `dwarvenpick_pool_total`

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
