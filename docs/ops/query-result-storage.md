---
title: Query result storage
parent: Operations
nav_order: 23
---

# Query result storage

## Design map

- **Domain model:** a fixed `max-persisted-result-bytes` quota bounds all persisted result pages in the shared application database; storage usage, headroom, and a rejected page are byte-valued concepts.
- **Orchestration:** `QueryExecutionManager` streams JDBC rows into bounded pages and converts storage-budget exhaustion into a successful, explicitly truncated result without changing query history or audit persistence.
- **I/O adapters:** `QueryResultPersistenceRepository` serializes pages, takes the database coordination lock, measures committed payload usage, and inserts or rejects the page atomically. Metrics refresh reads logical payload and PostgreSQL table size through the same persistence boundary.
- **External contracts:** query status/result APIs, RBAC checks, audit events, runtime table names, result retention defaults, and the existing per-query/per-instance limits remain compatible. The new deployment contract is `DWARVENPICK_QUERY_MAX_PERSISTED_RESULT_BYTES`.
- **Validation boundary:** configuration must provide a positive fixed byte limit; each page is checked against the shared committed-byte total inside the storage transaction. No HPA or autoscaling behavior is introduced.

## Capacity policy

PostgreSQL remains the result store while the aggregate payload budget can absorb the fixed-replica worst case and the
observed result-write latency and WAL volume remain inside the database service objectives. Start with a budget no
larger than the database capacity reserved for ephemeral results after preserving headroom for sessions, history,
audit, and operational maintenance. Move payloads behind a compatible chunked/object-storage adapter before raising
the budget when either result pages consume 20% of the application database allocation, result-persistence p95 exceeds
250 ms for 15 minutes, or result writes consume 20% of sustained database I/O or WAL capacity.

The configured quota is fixed. When a page would exceed it, the backend stops reading the result, records a storage
budget rejection, and completes the query with a truncated-result message. Metadata, query history, and audit events
remain available.

## Retention objective

Completed result pages remain available for `dwarvenpick.query.result-session-ttl-seconds` after their last access
(10 minutes by default). The cleanup scheduler checks every `dwarvenpick.query.cleanup-interval-ms` (30 seconds by
default), so expired payloads should be reclaimed within one cleanup interval after the idle TTL. Runtime metadata is
retained independently.
