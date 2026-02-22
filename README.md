# dwarvenpick (OpenSQL Workbench)

`dwarvenpick` is an enterprise-focused, web-based SQL workbench for secure ad-hoc querying across multiple data platforms.

## Repository layout

- `backend/`: Kotlin + Spring Boot multi-module backend
- `frontend/`: React + TypeScript web application
- `deploy/`: Docker and Helm deployment assets
- `docs/roadmap/`: Product spec and milestone roadmap

## Developer prerequisites

- Java 21 (for backend Gradle/Spring Boot tasks)
- Node.js 22 + npm (for frontend dev/build/test)
- Docker + Docker Compose (optional, for local full-stack runs)

## Quick start (skeleton)

### Backend

```bash
./gradlew :backend:app:bootRun
```

Health endpoint:

- `GET http://localhost:8080/api/health`
- `GET http://localhost:8080/actuator/health`
- `GET http://localhost:8080/api/version`

Authentication endpoints:

- `GET http://localhost:8080/api/auth/csrf`
- `GET http://localhost:8080/api/auth/methods`
- `POST http://localhost:8080/api/auth/login`
- `POST http://localhost:8080/api/auth/ldap/login`
- `GET http://localhost:8080/api/auth/me`
- `POST http://localhost:8080/api/auth/logout`
- `GET http://localhost:8080/api/auth/admin/users` (`SYSTEM_ADMIN` only)
- `POST http://localhost:8080/api/auth/admin/users` (`SYSTEM_ADMIN` only, local auth enabled)
- `POST http://localhost:8080/api/auth/admin/users/{username}/reset-password` (`SYSTEM_ADMIN` only)

RBAC and datasource governance endpoints:

- `GET http://localhost:8080/api/admin/groups` (`SYSTEM_ADMIN` only)
- `POST http://localhost:8080/api/admin/groups` (`SYSTEM_ADMIN` only)
- `POST http://localhost:8080/api/admin/groups/{groupId}/members` (`SYSTEM_ADMIN` only)
- `PUT http://localhost:8080/api/admin/datasource-access/{groupId}/{datasourceId}` (`SYSTEM_ADMIN` only)
- `GET http://localhost:8080/api/admin/drivers` (`SYSTEM_ADMIN` only)
- `GET http://localhost:8080/api/admin/datasource-management` (`SYSTEM_ADMIN` only)
- `POST http://localhost:8080/api/admin/datasource-management` (`SYSTEM_ADMIN` only)
- `PUT http://localhost:8080/api/admin/datasource-management/{datasourceId}/credentials/{profileId}` (`SYSTEM_ADMIN` only)
- `POST http://localhost:8080/api/admin/datasource-management/credentials/reencrypt` (`SYSTEM_ADMIN` only)
- `POST http://localhost:8080/api/datasources/{datasourceId}/test-connection` (`SYSTEM_ADMIN` only)
- `GET http://localhost:8080/api/datasources` (user-scoped datasource visibility)
- `POST http://localhost:8080/api/queries` (datasource access gate check)
- `GET http://localhost:8080/api/queries/{executionId}/export.csv?headers=true|false` (RBAC export gate + audit)
- `GET http://localhost:8080/api/queries/history` (user-scoped history, admin can filter by actor)
- `GET http://localhost:8080/api/admin/audit-events` (`SYSTEM_ADMIN` only)

Default local development users:

- `admin / Admin1234!` (roles: `SYSTEM_ADMIN`, `USER`)
- `analyst / Analyst123!` (role: `USER`)
- `disabled.user / Disabled123!` (disabled account, login blocked)

Query history and audit retention settings:

- `dwarvenpick.query.history-retention-days` (default `30`)
- `dwarvenpick.query.audit-retention-days` (default `90`)
- `dwarvenpick.query.query-text-redaction-days` (default `0`, disabled)
- `dwarvenpick.query.retention-cleanup-interval-ms` (default `3600000`)

Compliance note:

- Set `query-text-redaction-days` to a non-zero value to remove stored SQL text after the configured age while preserving query hash, status, timing, and datasource metadata for auditability.

### Frontend

```bash
cd frontend
npm install
npm run dev
```

Default local UI URL:

- `http://localhost:5173`
- Dev server proxies `/api` and `/actuator` requests to `http://localhost:8080`.
- `SYSTEM_ADMIN` users see RBAC and datasource management panels in `/workspace`.
- `/workspace` includes Monaco SQL editor tabs with per-tab datasource context, Run Selection/Run All controls, and keyboard shortcuts (`Ctrl/Cmd+Enter`, `Esc`).

### Local stack (Docker Compose)

```bash
docker compose -f deploy/docker/docker-compose.yml up --build
```

Seeded databases in local compose:

- PostgreSQL metadata + sample source: `localhost:5432`, db `dwarvenpick`, user `dwarvenpick`, password `dwarvenpick`
- MySQL sample source: `localhost:3306`, db `orders`, user `readonly`, password `readonly`
- MariaDB sample source: `localhost:3307`, db `warehouse`, user `readonly`, password `readonly`

The MariaDB dataset includes transactional tables (`orders`, `order_items`, `inventory_snapshots`) and analytical structures (`fact_daily_sales`, `v_customer_ltv`) so you can test query/explain behavior with both OLTP and OLAP-style patterns.

## Local user management (UI)

- In `/workspace` open `Governance` and use the `Local Users` panel.
- `Create Local User` supports:
  - Temporary password (user must rotate on next login).
  - Permanent password.
  - Optional `SYSTEM_ADMIN` role assignment.
- Existing local users can be password-reset from the same panel, with temporary/permanent choice during reset.
- When local auth is disabled (LDAP-only mode), user creation/reset is intentionally unavailable in UI.

## License

Apache-2.0. See `LICENSE`.
