# badgermole Jira-Style Backlog

## Global Definition of Done

Applies to every ticket:

- Code merged via PR with review.
- Unit tests added or updated where applicable.
- Integration tests added for backend flows that touch DB/auth/query execution.
- Basic docs updated (`/docs` or `README`) when behavior/config changes.
- No secrets in logs; error messages sanitized for end users.
- Lint and format checks pass (Kotlin + TS).

---

## Milestone 0: Repo + Foundation

### EPIC: OSW-E0 Foundation & Delivery Pipeline

#### OSW-1: Initialize monorepo structure

- Type: Task
- Status: done
- Component: Repo/Platform
- Description: Create repo layout and baseline docs.
- Acceptance Criteria:
  - Repo has `/backend`, `/frontend`, `/deploy`, `/docs`.
  - `README` includes dev prerequisites and local run instructions.
  - `LICENSE` is Apache-2.0 and basic `CONTRIBUTING.md` exists.
- Dependencies: None

#### OSW-2: Gradle multi-module build (Kotlin DSL)

- Type: Task
- Status: done
- Component: Backend
- Description: Set up Gradle with Kotlin DSL for multi-module backend.
- Acceptance Criteria:
  - `./gradlew test` runs successfully on clean checkout.
  - Modules compile: `backend:app`, `backend:core`, `backend:db`.
  - Kotlin compiler settings standardized (JVM target, warnings).
- Dependencies: OSW-1

#### OSW-3: Spring Boot backend scaffold + health endpoints

- Type: Story
- Status: done
- Component: Backend
- Description: Create Spring Boot app skeleton with Actuator.
- Acceptance Criteria:
  - `GET /actuator/health` returns `UP`.
  - `GET /api/version` returns app version/build metadata.
  - Build produces runnable jar.
- Dependencies: OSW-2

#### OSW-4: React + TypeScript frontend scaffold

- Type: Story
- Status: done
- Component: Frontend
- Description: Create UI skeleton with routing and placeholder pages.
- Acceptance Criteria:
  - App loads shell pages with routing (for example `/login`, `/workspace`).
  - Lint and typecheck pass.
  - Dev server runs and proxies to backend.
- Dependencies: OSW-1

#### OSW-5: Docker builds for backend + frontend

- Type: Story
- Status: done
- Component: DevOps
- Description: Add production-grade Dockerfiles and a local compose setup.
- Acceptance Criteria:
  - `docker build` succeeds for both services.
  - `docker compose up` brings up backend, frontend, and Postgres.
  - Backend connects to Postgres in compose environment.
- Dependencies: OSW-3, OSW-4

#### OSW-6: CI pipeline (build/lint/test)

- Type: Story
- Status: done
- Component: DevOps
- Description: Add CI workflow (GitHub Actions or internal CI) for PR gating.
- Acceptance Criteria:
  - CI runs on PR: backend build+tests, frontend build+tests.
  - CI fails PR if lint/test fails.
  - CI produces build artifacts (optional).
- Dependencies: OSW-2, OSW-4

#### OSW-7: Helm chart scaffold (K8s deployment skeleton)

- Type: Task
- Status: done
- Component: DevOps
- Description: Provide initial Helm chart with Deployments, Services, Ingress placeholders.
- Acceptance Criteria:
  - `helm template` renders without errors.
  - Values include image tags, env vars, and Postgres connection.
  - Readiness/liveness probes included (placeholder endpoints allowed).
- Dependencies: OSW-5

#### OSW-8: Baseline code quality tooling

- Type: Task
- Status: backlog
- Component: Repo/Platform
- Description: Add formatting/lint rules.
- Acceptance Criteria:
  - Kotlin linting (`ktlint` or `detekt`) wired into Gradle.
  - TS linting and formatting (`ESLint` + `Prettier`) wired into frontend scripts.
  - CI runs these checks.
- Dependencies: OSW-2, OSW-4, OSW-6

---

## Milestone 1: Postgres schema + migrations + core services

### EPIC: OSW-E1 Metadata DB & Core Domain

#### OSW-20: Add Flyway + initial schema migrations

- Type: Story
- Status: backlog
- Component: Backend/DB
- Description: Set up Flyway and create v1 schema for users/roles/groups/datasources/audit skeleton.
- Acceptance Criteria:
  - App boots and automatically runs migrations.
  - Tables exist: `users`, `roles`, `user_roles`, `groups`, `group_members`, `audit_events`, `datasources`, `datasource_credentials`, `group_datasource_access`.
  - Migration scripts are idempotent and documented.
- Dependencies: OSW-3, OSW-5

#### OSW-21: Data access layer (jOOQ) setup

- Type: Story
- Status: backlog
- Component: Backend/DB
- Description: Configure jOOQ codegen and repository pattern.
- Acceptance Criteria:
  - jOOQ code generation runs in CI.
  - Repositories can query/insert into users and groups.
  - No raw SQL scattered in service layer (except migrations).
- Dependencies: OSW-20

#### OSW-22: Core service: Users & Roles (backend only)

- Type: Story
- Status: backlog
- Component: Backend
- Description: Implement user CRUD and role assignment service methods (no UI yet).
- Acceptance Criteria:
  - Service methods exist for create/disable user and assign role.
  - Unit tests cover happy path and validation failures.
  - Audit event written for user creation/disable.
- Dependencies: OSW-21

#### OSW-23: Core service: Groups & Membership (backend only)

- Type: Story
- Status: backlog
- Component: Backend
- Description: Implement groups CRUD and add/remove members.
- Acceptance Criteria:
  - Create/update/delete group supported.
  - Add/remove members supported.
  - Audit events recorded for membership changes.
  - Integration test verifies persistence with Testcontainers Postgres.
- Dependencies: OSW-21

#### OSW-24: Core service: Datasources & Credentials (backend only)

- Type: Story
- Status: backlog
- Component: Backend
- Description: Implement datasource metadata CRUD and credential profile storage (encrypted later in M4).
- Acceptance Criteria:
  - Datasource create/update/delete supported (without connection testing yet).
  - Credentials create/update/delete supported (placeholder encryption field allowed).
  - Permissions mapping stub exists (full enforcement in M3).
- Dependencies: OSW-21

#### OSW-25: Audit logging helper and event taxonomy

- Type: Task
- Status: backlog
- Component: Backend/Security
- Description: Implement an audit logger utility and standardize event names.
- Acceptance Criteria:
  - Single helper writes audit events with actor, IP, action, resource, JSON details.
  - Event naming documented in `/docs/audit-events.md`.
  - Audit writes occur in OSW-22/23/24 flows.
- Dependencies: OSW-20

#### OSW-26: Backend API scaffolding for admin CRUD (stub responses allowed)

- Type: Story
- Status: backlog
- Component: Backend
- Description: Add REST endpoints for users/groups/datasources to unblock UI later.
- Acceptance Criteria:
  - Endpoints exist behind temporary dev auth gate.
  - Request/response DTOs defined and versioned (`/api/v1`).
  - OpenAPI spec generated (optional but recommended).
- Dependencies: OSW-22, OSW-23, OSW-24

---

## Milestone 2: Authentication (local + LDAP)

### EPIC: OSW-E2 Authentication & Sessions

#### OSW-40: Implement local auth (sessions + login/logout)

- Type: Story
- Status: backlog
- Component: Backend/Security
- Description: Add username/password login and HTTP-only cookie sessions.
- Acceptance Criteria:
  - `POST /api/auth/login` sets secure session cookie.
  - `POST /api/auth/logout` invalidates session.
  - `GET /api/auth/me` returns current user profile.
  - Passwords stored with bcrypt.
  - Integration tests cover login success/failure.
- Dependencies: OSW-22, OSW-26

#### OSW-41: Implement password policy + admin reset flow

- Type: Story
- Status: backlog
- Component: Backend/Security
- Description: Enforce minimum password requirements and allow admin reset.
- Acceptance Criteria:
  - Configurable minimum length and basic complexity.
  - Admin password reset supported.
  - Failed login attempts are logged (audit).
  - User status `disabled` blocks login.
- Dependencies: OSW-40

#### OSW-42: LDAP authentication (bind + user lookup)

- Type: Story
- Status: backlog
- Component: Backend/Security
- Description: Allow users to authenticate via LDAP.
- Acceptance Criteria:
  - Config supports LDAP URL, bind DN, search base, user filter, and attribute mapping.
  - `POST /api/auth/ldap/login` authenticates via LDAP.
  - On first login, user is auto-provisioned in `users`.
  - Audit event recorded for LDAP login.
- Dependencies: OSW-26, OSW-20

#### OSW-43: LDAP group sync (optional but recommended)

- Type: Story
- Status: backlog
- Component: Backend/Security
- Description: Map LDAP groups to internal groups.
- Acceptance Criteria:
  - Config supports LDAP group filter and mapping rules.
  - On login (or scheduled sync), internal memberships match LDAP mapping.
  - Changes are audited (added/removed memberships).
- Dependencies: OSW-42, OSW-23

#### OSW-44: Frontend login UX (local + LDAP)

- Type: Story
- Status: backlog
- Component: Frontend
- Description: Implement `/login` page with auth method selection and error handling.
- Acceptance Criteria:
  - Users can login via local or LDAP.
  - Errors show friendly messages (no stack traces).
  - Successful login redirects to `/workspace`.
- Dependencies: OSW-40, OSW-42, OSW-4

#### OSW-45: Security baseline: CSRF + security headers

- Type: Task
- Status: backlog
- Component: Backend/Security
- Description: Add CSRF protection and baseline headers.
- Acceptance Criteria:
  - CSRF enabled for state-changing requests.
  - Security headers set (for example `X-Content-Type-Options`, `Referrer-Policy`).
  - Documented in `/docs/security-baseline.md`.
- Dependencies: OSW-40

---

## Milestone 3: RBAC + group-based datasource access

### EPIC: OSW-E3 Authorization & Governance

#### OSW-60: Implement RBAC roles and permission checks

- Type: Story
- Status: backlog
- Component: Backend/Security
- Description: Add role enforcement using Spring Security method/route protection.
- Acceptance Criteria:
  - Admin endpoints require `SYSTEM_ADMIN`.
  - User endpoints require authenticated session.
  - Unauthorized calls return 403 and unauthenticated calls return 401.
  - Integration tests verify enforcement.
- Dependencies: OSW-40, OSW-22, OSW-26

#### OSW-61: Group management UI (admin)

- Type: Story
- Status: backlog
- Component: Frontend
- Description: Admin can create groups and add/remove users.
- Acceptance Criteria:
  - Admin can list/create/edit groups.
  - Admin can add/remove members.
  - Audit events visible in DB for group actions.
- Dependencies: OSW-23, OSW-60

#### OSW-62: Datasource access mapping API (group -> datasource)

- Type: Story
- Status: backlog
- Component: Backend
- Description: Implement `group_datasource_access` CRUD with fields like `can_query`, `can_export`, and runtime limits.
- Acceptance Criteria:
  - Admin can grant/revoke group access to datasource.
  - Access includes credential profile binding.
  - Changes audited.
- Dependencies: OSW-24, OSW-60

#### OSW-63: Datasource access mapping UI (admin)

- Type: Story
- Status: backlog
- Component: Frontend
- Description: UI to assign datasource permissions to groups.
- Acceptance Criteria:
  - Admin can select a group and assign datasources.
  - Admin can set `can_query`/`can_export` and limits.
  - UI prevents invalid configs (for example missing credential profile).
- Dependencies: OSW-62, OSW-61

#### OSW-64: Enforce datasource access checks in query APIs (gate only)

- Type: Story
- Status: backlog
- Component: Backend/Security
- Description: Before executing any query, verify user group grants access to datasource.
- Acceptance Criteria:
  - Users see only permitted datasources in `/api/datasources` (user-scoped list).
  - Querying a forbidden datasource returns 403 and is audited.
  - Integration tests cover allowed/denied.
- Dependencies: OSW-62, OSW-60

---

## Milestone 4: Datasource management + driver registry

### EPIC: OSW-E4 Multi-DB Connectivity & Secure Secrets

#### OSW-80: Encrypt datasource credentials at rest

- Type: Story
- Status: backlog
- Component: Backend/Security
- Description: Encrypt stored DB passwords using AES-GCM with a master key provided by env/secret.
- Acceptance Criteria:
  - Passwords stored encrypted in `datasource_credentials.password_encrypted`.
  - Master key not stored in DB or logs.
  - Rotation strategy documented (at minimum: new key id + re-encrypt tool).
- Dependencies: OSW-24

#### OSW-81: Datasource connection testing endpoint

- Type: Story
- Status: backlog
- Component: Backend
- Description: Admin can test connectivity using selected credential profile and SSL options.
- Acceptance Criteria:
  - `POST /api/datasources/{id}/test-connection` returns success/failure with sanitized message.
  - TLS/SSL config supported (`require` plus optional verification flags).
  - Audit event recorded for test attempt.
- Dependencies: OSW-80

#### OSW-82: Connection pool manager per datasource/credential

- Type: Story
- Status: backlog
- Component: Backend
- Description: Create pool manager (HikariCP) keyed by datasource + credential profile.
- Acceptance Criteria:
  - Pools created lazily and reused.
  - Pool sizes configurable per datasource (JSON/config).
  - Metrics exposed for active/idle connections per pool.
- Dependencies: OSW-81

#### OSW-83: Driver registry (built-in + external JARs via mounted volume)

- Type: Story
- Status: backlog
- Component: Backend/Platform
- Description: Support built-in drivers and external driver jars mounted at runtime.
- Acceptance Criteria:
  - Built-in drivers load for Postgres/MySQL/MariaDB/Trino.
  - External jar directory configurable (for example `/opt/app/drivers`).
  - Admin can select driver for datasource type where multiple options exist.
  - Docs explain adding driver jars in K8s and bare metal.
- Dependencies: OSW-3

#### OSW-84: Implement JDBC connectors: Postgres/MySQL/MariaDB

- Type: Story
- Status: backlog
- Component: Backend
- Description: Ensure basic query execution connectivity works (no editor yet).
- Acceptance Criteria:
  - Connection test succeeds for Postgres, MySQL, MariaDB.
  - Basic `SELECT 1` can be executed in test harness.
  - Driver versions pinned and documented.
- Dependencies: OSW-83, OSW-82

#### OSW-85: Implement JDBC connectors: Trino + StarRocks

- Type: Story
- Status: backlog
- Component: Backend
- Description: Add driver support and recommended configs.
- Acceptance Criteria:
  - Trino JDBC driver integrated and testable.
  - StarRocks configured (MySQL protocol) with documented driver selection strategy.
  - Connection test endpoint works for both.
- Dependencies: OSW-83, OSW-81

#### OSW-86: Vertica driver support via external JAR

- Type: Story
- Status: backlog
- Component: Backend
- Description: Add Vertica datasource type that requires external driver.
- Acceptance Criteria:
  - Datasource type `vertica` supported.
  - If driver jar is missing, UI/API returns actionable error.
  - Connection test works when jar is present.
  - Driver installation documented without bundling the jar.
- Dependencies: OSW-83, OSW-81

#### OSW-87: Datasource admin UI (CRUD + test connection)

- Type: Story
- Status: backlog
- Component: Frontend
- Description: Admin UI to create/edit datasources, manage credential profiles, and test connections.
- Acceptance Criteria:
  - Datasource list and create/edit flows exist.
  - Credential profiles can be added and updated.
  - Test Connection shows clear result and does not leak secrets.
- Dependencies: OSW-81, OSW-80

---

## Milestone 5: SQL editor MVP (tabs + highlighting)

### EPIC: OSW-E5 Web SQL Workspace MVP

#### OSW-100: Monaco editor integration with SQL highlighting

- Type: Story
- Status: backlog
- Component: Frontend
- Description: Add Monaco editor with SQL language mode and basic settings.
- Acceptance Criteria:
  - SQL syntax highlighting works.
  - Line numbers, bracket matching, and basic search are available.
  - Editor handles large queries without lag (basic sanity check).
- Dependencies: OSW-4

#### OSW-101: Tabbed editor model (multiple tabs per datasource)

- Type: Story
- Status: backlog
- Component: Frontend
- Description: Implement multi-tab workspace with per-tab datasource context.
- Acceptance Criteria:
  - User can open/close/rename tabs.
  - Each tab stores datasource id, optional schema, and query text.
  - Tabs persist in browser storage and restore on refresh.
- Dependencies: OSW-100

#### OSW-102: Datasource picker (user-scoped)

- Type: Story
- Status: backlog
- Component: Frontend
- Description: Show only datasources user is allowed to query and bind selection to tab.
- Acceptance Criteria:
  - Dropdown shows user-permitted datasources only.
  - Switching datasource prompts or clearly indicates context change.
  - Forbidden datasource cannot be selected even if tampered.
- Dependencies: OSW-64, OSW-101

#### OSW-103: Run actions: run selection / run all

- Type: Story
- Status: backlog
- Component: Frontend
- Description: Add Run controls and wire to backend execute endpoint (stub allowed until M6).
- Acceptance Criteria:
  - Run selection sends selected SQL text.
  - Run all sends full editor content.
  - UI shows executing state and disables duplicate runs per tab.
- Dependencies: OSW-101, OSW-102

#### OSW-104: Keyboard shortcuts (DataGrip-ish basics)

- Type: Task
- Status: backlog
- Component: Frontend
- Description: Add common shortcuts.
- Acceptance Criteria:
  - Ctrl/Cmd+Enter runs selection or statement (MVP can be selection/full).
  - Esc cancels running query (wired in M6).
  - Shortcut list documented in UI help.
- Dependencies: OSW-103

---

## Milestone 6: Query execution engine (jobs + pagination + cancel)

### EPIC: OSW-E6 Query Engine & Multi-User Execution

#### OSW-120: Query execution DB model + API contract

- Type: Story
- Status: backlog
- Component: Backend
- Description: Finalize `query_executions` schema fields and API DTOs.
- Acceptance Criteria:
  - Migration adds `query_executions` with status lifecycle fields.
  - API endpoints defined:
    - `POST /api/queries`
    - `POST /api/queries/{id}/cancel`
    - `GET /api/queries/{id}`
    - `GET /api/queries/{id}/results`
  - Query text stored and `query_hash` computed.
- Dependencies: OSW-20, OSW-64

#### OSW-121: QueryExecutionManager (lifecycle + worker execution)

- Type: Story
- Status: backlog
- Component: Backend
- Description: Implement manager that runs queries in background (virtual threads) and tracks state.
- Acceptance Criteria:
  - States: queued -> running -> succeeded/failed/canceled.
  - Errors sanitized (no creds/stack traces returned to user).
  - Audit event written for query start/end/cancel.
- Dependencies: OSW-120, OSW-82

#### OSW-122: Cursor session + bounded buffering for pagination

- Type: Story
- Status: backlog
- Component: Backend
- Description: Implement cursor-based paging without loading all rows.
- Acceptance Criteria:
  - `GET /results` returns columns, rows, and `nextPageToken`.
  - Memory usage bounded (configurable max buffered rows/pages).
  - Session expires after inactivity and cleans up JDBC resources.
- Dependencies: OSW-121

#### OSW-123: Query cancellation (soft + hard fallback)

- Type: Story
- Status: backlog
- Component: Backend
- Description: Support cancel via `Statement.cancel()` and fallback to connection close.
- Acceptance Criteria:
  - Cancel transitions query to canceled state.
  - Cancel works for a long-running query test scenario on Postgres at minimum.
  - Hard cancel is used if soft cancel fails or times out.
- Dependencies: OSW-121

#### OSW-124: Enforce timeouts + limits (per datasource/group)

- Type: Story
- Status: backlog
- Component: Backend/Security
- Description: Apply policy limits for runtime, rows, and concurrency.
- Acceptance Criteria:
  - Runtime limit enforced (statement timeout where supported + server-side guard).
  - Row fetch limit enforced for UI paging and exports (limits may differ).
  - Per-user concurrency limit enforced.
  - Denied/limited actions audited.
- Dependencies: OSW-62, OSW-121

#### OSW-125: Query events channel (SSE or WebSocket)

- Type: Story
- Status: backlog
- Component: Backend + Frontend
- Description: Stream query status updates to UI to avoid polling.
- Acceptance Criteria:
  - UI receives running/completed/canceled updates without refresh.
  - On reconnect, UI can re-sync status for in-flight queries.
  - Events contain no sensitive fields.
- Dependencies: OSW-121, OSW-103

#### OSW-126: Frontend integration: run/cancel + results fetch

- Type: Story
- Status: backlog
- Component: Frontend
- Description: Wire editor to query API and display results (basic).
- Acceptance Criteria:
  - Running query shows status, then first page.
  - Pagination controls fetch next pages.
  - Cancel button cancels and UI reflects canceled state.
- Dependencies: OSW-122, OSW-123, OSW-125

#### OSW-127: Query engine integration tests (Testcontainers)

- Type: Story
- Status: backlog
- Component: Backend/QA
- Description: Build automated tests for execute/paginate/cancel using containerized DBs.
- Acceptance Criteria:
  - Tests cover Postgres execute + paginate + cancel.
  - Tests cover at least one MySQL-compatible DB execute.
  - CI runs tests reliably.
- Dependencies: OSW-121, OSW-122, OSW-123

#### OSW-128: Cleanup job for expired executions/resources

- Type: Task
- Status: backlog
- Component: Backend
- Description: Scheduled cleanup of expired cursor sessions and stuck executions.
- Acceptance Criteria:
  - Expired sessions close `ResultSet`, `Statement`, and `Connection`.
  - Stuck running queries older than threshold are flagged and canceled (optional).
  - Cleanup actions logged and optionally audited.
- Dependencies: OSW-122

---

## Milestone 7: Results grid + CSV export

### EPIC: OSW-E7 Results UX & Exports

#### OSW-140: Results grid component (paginated + virtualized)

- Type: Story
- Status: backlog
- Component: Frontend
- Description: Implement performant result table for large pages.
- Acceptance Criteria:
  - Renders headers and rows.
  - Handles wide tables (horizontal scroll).
  - Virtualized rendering prevents UI lockups.
  - Page navigation supported via backend tokens.
- Dependencies: OSW-126

#### OSW-141: Copy-to-clipboard actions (cell/row)

- Type: Task
- Status: backlog
- Component: Frontend
- Description: Add copy cell and copy row as CSV.
- Acceptance Criteria:
  - Copy cell copies raw value.
  - Copy row copies CSV row with correct quoting.
  - Works on large result sets without noticeable lag.
- Dependencies: OSW-140

#### OSW-142: Streaming CSV export endpoint (headers optional)

- Type: Story
- Status: backlog
- Component: Backend
- Description: Implement CSV export as a streamed response.
- Acceptance Criteria:
  - `GET /api/queries/{id}/export.csv?headers=true|false` exists.
  - CSV is streamed without full memory buffering.
  - Correct escaping for commas/quotes/newlines.
  - Export obeys `can_export` permission and export limits.
  - Export attempts audited.
- Dependencies: OSW-124, OSW-122

#### OSW-143: Export options UI

- Type: Story
- Status: backlog
- Component: Frontend
- Description: Modal to export with/without headers (delimiter later).
- Acceptance Criteria:
  - User can choose include headers true/false.
  - Export triggers file download.
  - UI shows permission errors cleanly.
- Dependencies: OSW-142, OSW-140

#### OSW-144: Export test suite (golden files)

- Type: Story
- Status: backlog
- Component: Backend/QA
- Description: Add tests verifying CSV output correctness.
- Acceptance Criteria:
  - Tests cover headers on/off, null values, quotes/newlines, and unicode.
  - Tests run in CI.
- Dependencies: OSW-142

---

## Milestone 8: Query history + audit UI

### EPIC: OSW-E8 History, Audit, and Compliance

#### OSW-160: Persist query execution metadata (history)

- Type: Story
- Status: backlog
- Component: Backend
- Description: Save history for every query with safe error messages and hashes.
- Acceptance Criteria:
  - Queries persist status, duration, datasource, and hash.
  - Query text stored (configurable; redaction handled separately).
  - Failed queries store sanitized error summary.
  - History entries visible via API for current user.
- Dependencies: OSW-120, OSW-121

#### OSW-161: User query history UI (filter + rerun)

- Type: Story
- Status: backlog
- Component: Frontend
- Description: Add history panel and rerun workflow.
- Acceptance Criteria:
  - Filter by datasource/status/date range.
  - Clicking history entry loads SQL into a new tab.
  - Access checks still enforced on rerun.
- Dependencies: OSW-160, OSW-101

#### OSW-162: Admin audit log API + UI

- Type: Story
- Status: backlog
- Component: Backend + Frontend
- Description: Admin can view audit events with filtering.
- Acceptance Criteria:
  - Admin endpoint lists events with filters (action/user/time range).
  - UI page renders audit table.
  - Non-admin cannot access.
- Dependencies: OSW-25, OSW-60

#### OSW-163: Retention policies + pruning job

- Type: Story
- Status: backlog
- Component: Backend
- Description: Prune history/audit based on configured retention.
- Acceptance Criteria:
  - Configurable retention days for query history and audit events.
  - Background job removes expired records.
  - Job is safe and avoids excessive table locking (batch deletes).
- Dependencies: OSW-160, OSW-162

#### OSW-164: Optional query text redaction after N days

- Type: Story
- Status: backlog
- Component: Backend/Security
- Description: Replace `query_text` with `NULL` or `[REDACTED]` after configured interval while keeping hashes and metadata.
- Acceptance Criteria:
  - Redaction job runs daily (configurable).
  - History still shows metadata if query text is redacted.
  - Compliance rationale and config are documented.
- Dependencies: OSW-160

---

## Milestone 9: Hardening + Operations

### EPIC: OSW-E9 Production Readiness

#### OSW-180: Production Helm chart (secrets, ingress, scaling knobs)

- Type: Story
- Status: backlog
- Component: DevOps
- Description: Turn Helm scaffold into a deployable chart for real environments.
- Acceptance Criteria:
  - Values support replicas, resources, ingress/TLS, and env vars for LDAP/DB/encryption keys.
  - Secrets pulled from K8s Secrets (no plaintext in values).
  - Chart includes PodDisruptionBudget and HPA-ready metrics (optional).
- Dependencies: OSW-7, OSW-80

#### OSW-181: Readiness/liveness + graceful shutdown behavior

- Type: Story
- Status: backlog
- Component: Backend
- Description: Ensure safe rollouts with in-flight queries.
- Acceptance Criteria:
  - Readiness flips to false during shutdown.
  - Graceful shutdown waits configurable time for queries to finish/cancel.
  - In-flight query sessions cleaned up on termination.
- Dependencies: OSW-121, OSW-128

#### OSW-182: Metrics via Micrometer + Prometheus endpoint

- Type: Story
- Status: backlog
- Component: Backend/Observability
- Description: Add metrics for queries, pools, auth failures, and exports.
- Acceptance Criteria:
  - `/actuator/prometheus` exposes metrics.
  - Metrics include active/running queries, duration histogram, cancels/timeouts, and pool active/idle.
  - Recommended alerts documented (optional).
- Dependencies: OSW-3, OSW-82, OSW-121

#### OSW-183: Structured JSON logging + request correlation IDs

- Type: Story
- Status: backlog
- Component: Backend/Observability
- Description: Make logs production-friendly and searchable.
- Acceptance Criteria:
  - Logs are JSON with consistent fields.
  - Every request has correlation id in logs and responses.
  - Query execution id included in query logs without leaking SQL unless configured.
- Dependencies: OSW-121

#### OSW-184: Frontend security hardening (CSP + build-time env config)

- Type: Story
- Status: backlog
- Component: Frontend/Security
- Description: Add CSP guidance and restrict risky behavior.
- Acceptance Criteria:
  - App compatible with strict CSP (documented).
  - No inline scripts required.
  - Client config does not expose secrets.
- Dependencies: OSW-4

#### OSW-185: SSRF and network guardrails for datasource definitions

- Type: Story
- Status: backlog
- Component: Backend/Security
- Description: Prevent datasources pointing to forbidden networks.
- Acceptance Criteria:
  - Configurable allowlist/denylist for hostnames and IP ranges.
  - Datasource save/test fails with clear error if forbidden.
  - Attempt audited.
- Dependencies: OSW-81

#### OSW-186: Concurrency and load test harness

- Type: Story
- Status: backlog
- Component: QA/Perf
- Description: Add repeatable load tests for multi-user query execution and exports.
- Acceptance Criteria:
  - Script simulates N users running queries and pagination.
  - Captures latency/error rate and key metrics.
  - Included in `/docs/perf-testing.md` with usage instructions.
- Dependencies: OSW-126, OSW-142, OSW-182

#### OSW-187: Deployment docs + runbooks

- Type: Task
- Status: backlog
- Component: Docs/DevOps
- Description: Document configuration and operational procedures.
- Acceptance Criteria:
  - Docs include LDAP setup, key management, external drivers (Vertica), metadata DB backup/restore, and sizing guidance.
  - Incident runbooks include DB unreachable, LDAP down, and stuck query scenarios.
- Dependencies: OSW-180

---

## Milestone 10: DataGrip-like polish (Phase 2+ backlog)

### EPIC: OSW-E10 DataGrip-Level UX Features

#### OSW-200: Schema browser (introspection) for Postgres/MySQL/MariaDB

- Type: Story
- Status: backlog
- Component: Backend + Frontend
- Description: Browse schemas/tables/columns with caching.
- Acceptance Criteria:
  - Sidebar tree loads schemas/tables/columns.
  - Results cached per datasource with TTL.
  - RBAC enforced (only permitted datasources visible).
- Dependencies: OSW-84, OSW-102

#### OSW-201: Monaco autocomplete provider (tables/columns)

- Type: Story
- Status: backlog
- Component: Frontend
- Description: Provide suggestions based on schema cache.
- Acceptance Criteria:
  - Autocomplete suggests table and column names.
  - Suggestions update when schema cache refreshes.
  - Works for Postgres initially.
- Dependencies: OSW-200

#### OSW-202: SQL statement splitter (semicolon-aware)

- Type: Story
- Status: backlog
- Component: Backend
- Description: Support run current statement without requiring user selection.
- Acceptance Criteria:
  - Correctly splits statements while respecting quotes/comments.
  - Run statement at cursor supported.
  - Unit tests cover tricky SQL cases.
- Dependencies: OSW-121

#### OSW-203: Saved queries/snippets (per user and per group)

- Type: Story
- Status: backlog
- Component: Backend + Frontend
- Description: Allow saving named snippets and reusing them.
- Acceptance Criteria:
  - Users can save/edit/delete personal snippets.
  - Optional group-shared snippets with permission controls.
  - Snippet usage audited (optional).
- Dependencies: OSW-23, OSW-60

#### OSW-204: SQL formatter integration

- Type: Story
- Status: backlog
- Component: Frontend (or Backend)
- Description: Add Format SQL action.
- Acceptance Criteria:
  - Format button formats selection or full query.
  - Formatting deterministic and safe.
  - Works offline (client-side) or via API with documented choice.
- Dependencies: OSW-100

#### OSW-205: Read-only vs read-write enforcement modes

- Type: Story
- Status: backlog
- Component: Backend/Security
- Description: Enforce per datasource/credential read-only mode with policy checks.
- Acceptance Criteria:
  - Read-only profiles block non-SELECT statements.
  - Violations return clear error and are audited.
  - Docs state DB-level permissions remain source of truth.
- Dependencies: OSW-62, OSW-124

#### OSW-206: Explain plan viewer (Postgres first)

- Type: Story
- Status: backlog
- Component: Backend + Frontend
- Description: Provide UI to run `EXPLAIN` and display plan output.
- Acceptance Criteria:
  - User can run explain from UI.
  - Output displayed in readable format.
  - Access checks enforced.
- Dependencies: OSW-84, OSW-103

#### OSW-207: Optional OIDC SSO (enterprise convenience)

- Type: Story
- Status: backlog
- Component: Backend/Security
- Description: Add OIDC provider support in addition to LDAP.
- Acceptance Criteria:
  - OIDC login works with a standard provider.
  - Roles/groups can be mapped from claims (optional).
  - Configuration documented.
- Dependencies: OSW-40, OSW-60
