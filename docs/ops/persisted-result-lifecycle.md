---
title: Persisted result lifecycle
parent: Operations
nav_order: 35
---

# Persisted result lifecycle

## Design map

- **Domain model:** a persisted result session is terminal query metadata, page rows, a last-access timestamp, and zero or more bounded export leases. The configured TTL is authoritative across backend processes.
- **Orchestration:** `PersistedQueryResultLifecycleService` observes storage and expiry lag, then asks the repository to expire one bounded batch. Query result reads and exports delegate session-touch and lease behavior to the same service.
- **I/O adapters:** `QueryRuntimeRepository` owns row locks, page deletion, storage aggregation, and durable export-lease persistence in the shared metadata database.
- **External contracts:** existing query/result HTTP statuses, RBAC checks, export audit events, page tokens, and CSV shape remain unchanged. New configuration and Prometheus metric names are additive.
- **Validation boundary:** query configuration supplies positive TTL, cleanup-batch, and export-lease limits; repository transactions re-check terminal state, persisted expiry, TTL cutoff, and active leases before returning or deleting result pages.

## Runtime behavior

Completed result pages expire from shared PostgreSQL according to persisted `last_accessed_at`, even when the backend that executed the query has stopped. A result read atomically refreshes that timestamp only while the session is still inside its TTL. A stale read marks the session expired and deletes its pages before returning the existing HTTP `410 Gone` result-expired contract.

CSV exports acquire a database lease before streaming. Cleanup skips an active lease, the stream renews a long-running lease, and normal completion releases it. A backend failure leaves only a time-bounded lease, so abandoned exports cannot retain storage indefinitely.

Configuration:

- `DWARVENPICK_QUERY_PERSISTED_RESULT_EXPIRY_DELETE_ENABLED` (default `true`) controls page deletion. Set it to `false` for the first observation phase of a rollout; candidate and lag metrics remain available.
- `DWARVENPICK_QUERY_PERSISTED_RESULT_EXPIRY_BATCH_SIZE` (default `100`, application-clamped to `1..10000`) bounds work in one cleanup transaction.
- `DWARVENPICK_QUERY_RESULT_EXPORT_LEASE_SECONDS` (default `900`, minimum `60`) bounds an abandoned export lease.
- `dwarvenpick.query.result-session-ttl-seconds` remains the result TTL (default `600`, minimum `60`).

## Fixed storage envelope

Do not use HPA to compensate for metadata storage pressure. Keep the reviewed fixed replica count and calculate a PostgreSQL result-page budget before changing result limits:

```text
maxResultBytes = dwarvenpick.query.max-buffered-bytes
retentionWindowSeconds = max(resultSessionTtlSeconds, resultExportLeaseSeconds)
throughputEnvelope = peakCompletedQueriesPerSecond * retentionWindowSeconds * maxResultBytes
residentEnvelope = fixedBackendReplicas * maxBufferedBytesPerInstance
reviewedResultPageBudget = max(throughputEnvelope, residentEnvelope) * headroomFactor
```

Use a documented, representative peak completion rate and a headroom factor of at least `1.2`. Provision the external metadata database so the reviewed result-page budget plus non-result tables, WAL, indexes, maintenance headroom, and provider free-space requirements fit without approaching the storage ceiling. Recalculate the budget whenever fixed replicas, TTL, lease duration, result limits, or expected throughput changes.

## Metrics and rollout checks

Each backend reports the same database-wide gauges; aggregate them with `max` across pods, not `sum`:

- `dwarvenpick_query_persisted_result_bytes`
- `dwarvenpick_query_persisted_result_pages`
- `dwarvenpick_query_persisted_result_expiry_candidates`
- `dwarvenpick_query_persisted_result_expiry_lag_seconds`
- `dwarvenpick_query_persisted_result_export_leases`
- `dwarvenpick_query_persisted_result_expiry_deletion_enabled`
- `dwarvenpick_query_persisted_result_cleanup_failures_total`

Observe candidates, bytes, pages, export leases, and managed PostgreSQL free capacity with deletion disabled first. Enable deletion in dev only after restart, pagination, concurrent CSV export, and cleanup-retry checks pass. Promote the same release and explicit configuration to production only after the dev evidence is attached to the release record.
