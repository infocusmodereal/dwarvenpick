---
title: Scalability design
parent: Operations
nav_order: 26
---

# Scalability design map

- **Domain model:** `QueryAdmissionLimits` names the actor, global, and per-connection execution budgets. `DatasourcePoolCapacity` makes the independently pooled credential-profile multiplier explicit.
- **Orchestration:** `QueryExecutionManager` validates RBAC policy, reserves admission, submits bounded work, acquires JDBC capacity, and only then moves a query from `QUEUED` to `RUNNING`.
- **IO adapters:** `QueryAdmissionRepository` owns transactional shared-database locking/counting; `DatasourcePoolManager` owns profile-keyed Hikari pools and JDBC acquisition.
- **External contracts:** existing per-actor policy limits remain intact. `DWARVENPICK_QUERY_MAX_ACTIVE_EXECUTIONS` and `DWARVENPICK_QUERY_MAX_ACTIVE_PER_CONNECTION` define fixed cluster-wide envelopes across replicas. Managed connection responses expose profile-aware pool capacity without returning secret material.
- **Validation boundary:** repository tests cover atomic PostgreSQL admission across actors and connections, local admission behavior, profile-aware pool calculations, overload responses, cancellation, and the `QUEUED`/`RUNNING` transition around JDBC acquisition.

No HPA or automatic replica changes are part of this design. Operators review fixed replica counts, admission limits, profile counts, and upstream database budgets together.
