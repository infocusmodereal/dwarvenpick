# Project Spec: OpenSQL Workbench (codename: `dwarvenpick`)

## 1) Purpose and vision

### Problem statement

Internal engineers need an enterprise-grade, web-based SQL client to connect to multiple database systems and safely run ad-hoc queries with:

- strong authentication (local password + LDAP)
- RBAC + group-based access to data sources
- audited query history
- reliable query cancellation
- robust editor UX (tabs, connection explorer, syntax highlighting)
- performant result viewing with pagination and CSV export

### Non-goals (explicit)

This project is **not** a BI tool:

- No charts/visualization layer
- No dashboards
- No scheduled reports
- No alerting (optional future)

### Target users

- Engineers / SREs / analysts doing debugging, investigations, validation, incident support
- DBAs / platform engineers managing connections and access policies

---

## 2) High-level requirements

### Must-have

- Web-based SQL editor with:
  - SQL syntax highlighting
  - Multiple tabs per connection
  - Run selection / run statement / run entire editor
  - Query results grid with server-side pagination
  - Export to CSV with or without headers
- Authentication:
  - Local password auth
  - LDAP auth (bind + optional group sync)
- Authorization / governance:
  - RBAC roles + groups
  - Users are assigned to groups
  - Groups grant access to specific data sources (and optionally permission level)
- Data sources (initial):
  - PostgreSQL
  - MySQL
  - MariaDB
  - StarRocks
  - Trino
  - Vertica
- Query management:
  - Cancel running queries
  - Query history (per user + admin view)
  - Audit logging (who queried what, when, where)
- Self-hosting:
  - Docker image
  - Helm chart for K8s
  - Works on bare metal with systemd
- Performance:
  - Supports many concurrent users without UI freezing or backend OOM
  - Large result sets handled safely (bounded memory)

### Should-have (phase 2)

- Schema browser (schemas/tables/columns/indexes)
- Autocomplete for table/column names (from schema cache)
- SQL formatting
- Saved queries/snippets
- Read-only / read-write connection modes
- Per-user and per-group query concurrency limits

### Nice-to-have (phase 3+)

- SSO via OIDC/SAML (in addition to LDAP)
- Sharing query tabs or snippets within a group
- Plugin system for additional drivers/auth providers
- Statement-level explain plan viewer

---

## 3) Architecture overview

### Component diagram (conceptual)

**Browser (UI)**

- Monaco editor
- Results grid
- Query history panel
- Connection explorer

⬇️ HTTPS (REST) + WebSocket/SSE (query events)

**Backend API (Kotlin / Spring Boot)**

- Auth service (local + LDAP)
- RBAC service (users/groups/permissions)
- Data source registry (connection definitions)
- Query execution service (jobs, cancellation, result pagination)
- Audit logging service
- Schema introspection service (optional cache)

⬇️ JDBC (to each target DB)

**External databases**

- Postgres/MySQL/MariaDB/StarRocks/Trino/Vertica

⬇️ JDBC

**App database (PostgreSQL)**

- Users, groups, permissions
- Data sources (metadata + encrypted secrets)
- Query history and audit events

### Optional add-ons (recommended)

- Redis (ephemeral query result caching / session caching)
- Object storage (S3/MinIO) for large CSV exports

---

## 4) Tech stack (recommended)

### Backend

- Kotlin 2.x
- Java 21 runtime (virtual threads)
- Spring Boot (MVC)
- Spring Security (form login + LDAP integration)
- PostgreSQL driver (for app DB)
- Flyway (migrations)
- jOOQ (preferred) or Exposed for DB access
- HikariCP (datasource pools)
- WebSocket or SSE for query progress + streaming
- Micrometer + Prometheus metrics
- OpenTelemetry tracing (optional but recommended)

### Frontend

- React + TypeScript
- Monaco Editor
- Results grid: TanStack Table + react-virtual or AG Grid Community
- Auth via HTTP-only secure cookies (recommended) or token auth

### Packaging / deployment

- Docker multi-stage build
- Helm chart
- Config via environment variables + config files
- Secrets via K8s Secrets / Vault (recommended); local dev supports `.env`

### License

- Apache-2.0

---

## 5) Core domain model and governance

### Roles (RBAC)

- **System Admin**
  - Manage users/groups
  - Manage data sources and drivers
  - View all audit logs and query history
- **Data Source Admin**
  - Manage specific data sources (connections, permissions)
- **User**
  - Run queries on allowed data sources
  - View own query history
- **Read-only User** (optional)
  - Can run queries but no exports (or limited exports)

### Groups

- Groups are primary access-control unit
- Users belong to one or more groups
- Groups are granted access to one or more data sources with permission levels

### Data source access policy

Group-to-datasource mapping includes:

- `can_query` (boolean)
- `can_export` (boolean)
- `max_rows_per_query` (int, optional)
- `max_runtime_seconds` (int, optional)
- `concurrency_limit` (int, optional)
- `credential_profile` (which DB credentials to use)

### Security stance

The tool is **not** a database firewall. Real data protection must be enforced with DB permissions (service accounts, views, grants, read-only users). Tool RBAC is an additional gate and auditing layer.

---

## 6) Data source support approach

### Connector strategy

Use JDBC for all initial targets.

- PostgreSQL: official PostgreSQL JDBC driver
- MySQL: MySQL Connector/J
- MariaDB: MariaDB Java client
- StarRocks: MySQL protocol compatibility + explicit driver selection
- Trino: Trino JDBC driver
- Vertica: Vertica JDBC (vendor-distributed in some cases)

### Driver management

Implement **Driver Registry**:

- Built-in packaged OSS-safe drivers
- Admin-provided driver JAR support for uncertain licenses (e.g., Vertica), mounted at `/opt/app/drivers/*.jar`

---

## 7) Query execution model

### Key constraints

- Huge results: no full in-memory loading
- Many concurrent users: bounded resources
- Must support pagination, export, cancellation, history

### Recommended pattern: Result Cursor Sessions

Store live `Connection + Statement + ResultSet` and cursor state with bounded row buffers.

### Lifecycle

1. User clicks Run
2. Backend creates `query_execution_id`
3. Backend allocates pooled connection + statement timeout
4. Query runs in background worker (virtual thread)
5. Backend streams status, metadata, first page
6. UI requests additional pages
7. Cursor expires after inactivity (e.g., 5–15 min), then cleanup

### Pagination behavior

- Configurable page size (default 100–500)
- Store current page + small lookahead buffer (e.g., max 5 pages)
- UI supports Next/Prev in MVP
- Jump-to-page deferred unless server-side spooling exists

### Cancellation

- Soft cancel: `Statement.cancel()` + mark cancel requested
- Hard cancel fallback: close JDBC connection, ensure pool eviction

### Query safety limits

- Max runtime per query
- Max rows fetched to UI
- Max export rows/size
- Max concurrent queries per user and datasource

### Multi-statement strategy

- MVP: single statement only
- Phase 2: semicolon-aware splitting (quotes/comments)
- Phase 3: dialect-aware parsing (optional)

---

## 8) Result grid and CSV export

### Result grid

- Table with column names/types
- Pagination controls
- Copy cell / copy row
- Optional in-grid search

### Export

- CSV options:
  - include headers
  - delimiter
  - quote policy
  - NULL representation
- Streamed export only (no large memory buffering)
- Support export of fetched rows and full stream
- For very large exports: stream response or generate object-store file with one-time URL

### Export audit

Log who exported, query execution id, datasource, and size/rows if known.

---

## 9) Authentication and authorization

### Local auth

- Username/email + password
- bcrypt hashes
- Optional forced rotation, admin reset, lockout after N failures

### LDAP auth

- Service bind or anonymous bind
- Search base, user filter, group filter
- Attribute mapping: username/display name/email
- Optional group sync (periodic or on-login)

### Session management

- HTTP-only secure cookie sessions
- CSRF enabled
- SameSite and secure flags
- Admin-driven session invalidation

### Authorization checks

Every API call validates authn + role + datasource group permission.

---

## 10) Audit logging and query history

### Query history (user-facing)

- Query text
- Datasource
- Start/end/duration
- Status (success/fail/cancel)
- Rows fetched (if known)
- Sanitized error message
- Re-open/re-run into new tab

### Audit log (admin-facing)

Audit all sensitive actions:

- login/logout
- failed logins
- datasource create/update/delete
- permission changes
- query execute/cancel
- export start/complete
- driver jar install/remove

### Retention

Configurable retention for history/audit and optional query text redaction after N days.

---

## 11) Metadata database schema (PostgreSQL)

Suggested tables:

- `users`, `user_passwords`, `ldap_identities`
- `roles`, `user_roles`, `groups`, `group_members`
- `datasources`, `datasource_credentials`
- `group_datasource_access`
- `query_executions`, `query_events`
- `audit_events`

Detailed fields are tracked in milestone artifacts and migration files.

---

## 12) Backend API design (REST + WebSocket)

### REST endpoints (examples)

- Auth
  - `POST /api/auth/login`
  - `POST /api/auth/ldap/login`
  - `POST /api/auth/logout`
  - `GET /api/auth/me`
- Users/groups
  - `GET /api/users`
  - `POST /api/users`
  - `PATCH /api/users/{id}`
  - `GET /api/groups`
  - `POST /api/groups`
  - `POST /api/groups/{id}/members`
- Datasources
  - `GET /api/datasources`
  - `POST /api/datasources`
  - `POST /api/datasources/{id}/test-connection`
  - `POST /api/datasources/{id}/credentials`
  - `POST /api/datasources/{id}/access`
- Query execution
  - `POST /api/queries`
  - `POST /api/queries/{id}/cancel`
  - `GET /api/queries/{id}`
  - `GET /api/queries/{id}/results?pageToken=...`
  - `GET /api/queries/history?user=me`
- Export
  - `GET /api/queries/{id}/export.csv?headers=true`
- Audit
  - `GET /api/audit`

### WebSocket channel

- `/ws/query-events`
  - started
  - columns available
  - page ready
  - progress (if supported)
  - finished/canceled/failed

---

## 13) Frontend UX specification

### Main screens

1. Login (select Local/LDAP if both enabled)
2. Workspace
   - Sidebar: datasource picker, schema browser (phase 2), query history
   - Main: tab bar + Monaco + run/cancel/export actions
   - Bottom: results grid + execution logs

### Tab behavior

- Each tab keeps datasource context, optional schema selection, and query content
- Tabs duplicable and optionally persisted to local storage

### Run workflow

- Run disables Run, enables Cancel
- Show execution status
- Show first page and fetch additional pages as requested

---

## 14) Performance and scalability requirements

- Java 21 virtual threads for query execution and export streaming
- Per-user/per-datasource concurrency caps
- Driver-specific `fetchSize` tuning
- Backpressure: fetch next chunk only when requested
- Active cursor expiration + cleanup

### K8s scaling notes

- Prefer stateless API service where possible
- Query sessions are stateful (live DB cursors)
- MVP can run single service with sticky sessions
- Phase 2 can split API and worker with queue

### Observability

- Metrics: active queries, durations, cancels/timeouts, export sizes, pool utilization
- Logs: structured JSON + correlation IDs
- Tracing: datasource + query id attributes (avoid raw query text leakage)

---

## 15) Security requirements

- TLS at ingress/reverse proxy
- Secure cookies + CSRF
- Input validation + output escaping (XSS prevention)
- Strict CSP
- Secrets encrypted at rest (AES-GCM + managed master key)
- Audit log immutability from UI
- Admin endpoints role-gated
- Optional datasource host/IP allowlists to reduce SSRF risk

---

## 16) Milestones and agent-friendly work breakdown

See `/docs/roadmap/milestones.md` for task-by-task execution plan from M0 to M10.

---

## 17) Definition of done

A milestone is done when:

- Unit + integration tests exist (Testcontainers where possible)
- Security checks pass (auth enforced, no secret leakage)
- Performance sanity checks are executed (concurrency + large results)
- Docs updated (deploy/config/admin guide)
- License compliance verified (especially JDBC drivers)

---

## 18) Suggested repo structure

```text
/backend
  /app
  /core
  /datasource
  /query
  /auth
  /db
/frontend
  /src
    /components
    /pages
    /editor
    /results
    /auth
/deploy
  /helm
  /docker
/docs
```
