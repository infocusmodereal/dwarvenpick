---
title: Query admission
parent: Operations
nav_order: 35
---

# Query admission

## Design map

- **Domain model:** `QueryAdmissionLimits` defines fixed actor, Connection, and cluster budgets; `QueryAdmissionResult`
  identifies the budget that rejected a request.
- **Orchestration:** `QueryExecutionManager` validates RBAC-derived policy, requests one durable reservation, dispatches an
  admitted query, and records audit and metric outcomes.
- **I/O adapters:** `QueryAdmissionRepository` serializes admission in the shared metadata database and
  `DatasourcePoolManager` acquires the target JDBC Connection.
- **External contracts:** query submission remains authenticated and audited; overload returns sanitized HTTP `429` with
  `Retry-After`; existing execution/status payloads remain compatible. Prometheus reports queue depth, wait duration,
  rejection reason, and pool saturation.
- **Validation boundary:** positive configured limits are normalized before repository use, and the repository counts only
  durable `QUEUED`/`RUNNING` rows while holding the cluster admission lock.

## Capacity contract

Admission is bounded by all three fixed limits:

- `DWARVENPICK_QUERY_MAX_CONCURRENCY_PER_USER` caps active work for one actor and is further restricted by RBAC policy.
- `DWARVENPICK_QUERY_MAX_CONCURRENCY_PER_CONNECTION` caps active work for each Connection.
- `DWARVENPICK_QUERY_MAX_CONCURRENCY_GLOBAL` caps active work across all Connections and backend replicas.

`QUEUED` and `RUNNING` reservations count toward every applicable budget. PostgreSQL admission holds a transaction-scoped
cluster advisory lock while it counts and inserts the reservation, so concurrent submissions to different backend replicas
cannot overrun a budget. The existing actor lock is also retained for per-user safety during mixed-version rolling updates.
Embedded H2 uses an in-process lock and remains intended for one local backend process.

An admitted execution stays `QUEUED` until the target JDBC Connection has been acquired. The accepted queue is therefore
bounded by the global limit, and Connection-pool waiters remain visible as queued work. A rejected request receives HTTP
`429 Too Many Requests`, `Retry-After: 1`, and a sanitized message indicating whether actor, Connection, or cluster capacity
is full. Clients should retry with jittered backoff and must not busy-loop.

Stale active reservations are failed by the existing heartbeat cleanup after
`DWARVENPICK_QUERY_ACTIVE_EXECUTION_STALE_SECONDS`, making capacity reclaimable after pod loss.

## Metrics

- `dwarvenpick_query_active{status="queued|running"}`: cluster-wide durable queue and running depth.
- `dwarvenpick_query_queue_wait_seconds{datasourceId=...}`: time from durable admission to Connection acquisition.
- `dwarvenpick_query_admission_rejections_total{reason="actor|connection|global",datasourceId=...}`: rejected submissions.
- `dwarvenpick_pool_active`, `dwarvenpick_pool_total`, and `dwarvenpick_pool_threads_awaiting`: datasource saturation.
