# Scalability admission and readiness design map

- **Domain model:** fixed `QueryAdmissionLimits` bound actor, aggregate, and per-datasource active work; a dispatcher lease represents one local execution slot.
- **Orchestration:** `QueryExecutionManager` validates RBAC-governed requests, reserves a durable queue row, waits for a dispatcher lease, acquires the JDBC connection, and only then marks the query `RUNNING`.
- **IO adapters:** `QueryAdmissionRepository` owns atomic metadata-Postgres locking/counting/insertion; `DatasourcePoolManager` owns pool sizing and connections; Spring Boot's datasource health contributor checks metadata Postgres.
- **External contracts:** existing query status names, 429 response, audit events, per-actor atomicity, datasource pool settings, and probe URLs stay stable. New fixed admission settings are environment-overridable; no HPA or unrestricted autoscaling is introduced.
- **Validation boundary:** configuration values are coerced to positive fixed bounds, admission locks are acquired in global/datasource/actor order, queue timeout releases every local permit, and readiness includes metadata DB health while liveness excludes shared dependencies.

SCA-001 remains deployment-owned: the downstream capacity model and values must account for observed replacement overlap, including init-container resources, without changing this application repository.
