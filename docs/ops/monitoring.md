# Monitoring and Alerts

## Metrics endpoint

- Prometheus metrics: `GET /actuator/prometheus`
- Health probes:
  - `GET /actuator/health/liveness`
  - `GET /actuator/health/readiness`

## Key metrics

- Query lifecycle:
  - `badgermole_query_active{status="queued|running"}`
  - `badgermole_query_execution_total{outcome=...}`
  - `badgermole_query_duration_seconds{outcome=...}`
  - `badgermole_query_cancel_total`
  - `badgermole_query_timeout_total`
- Exports:
  - `badgermole_query_export_attempts_total{outcome=...}`
- Auth:
  - `badgermole_auth_login_attempts_total{provider=...,outcome=...}`
- Pools:
  - `badgermole_pool_active`
  - `badgermole_pool_idle`
  - `badgermole_pool_total`

## Recommended alerts

1. High query failure rate:
   - Trigger: `increase(badgermole_query_execution_total{outcome="failed"}[5m]) / increase(badgermole_query_execution_total[5m]) > 0.2`
2. Query timeout burst:
   - Trigger: `increase(badgermole_query_timeout_total[5m]) > 10`
3. High queue pressure:
   - Trigger: `badgermole_query_active{status="queued"} > 20` for 10m
4. Pool saturation:
   - Trigger: `badgermole_pool_active / badgermole_pool_total > 0.9` for 5m
5. Login failure surge:
   - Trigger: `increase(badgermole_auth_login_attempts_total{outcome="failed"}[5m]) > 25`
