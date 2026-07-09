---
title: Security baseline
parent: Security
nav_order: 10
---

# Security baseline

## Session and authentication

- Authentication uses server-side HTTP sessions (`JSESSIONID`) with HTTP-only cookies.
- Sessions are stored through Spring Session JDBC.
- For HA (multi-replica) and redeploy-safe logins, use a shared PostgreSQL metadata DB.
- Local login and LDAP login are exposed as JSON endpoints:
  - `POST /api/auth/login`
  - `POST /api/auth/ldap/login`
- Logout invalidates the existing session:
  - `POST /api/auth/logout`

## CSRF protection

- CSRF protection is enabled for state-changing requests.
- Backend issues CSRF tokens with Spring Security's cookie token repository.
- Frontend login flow retrieves a token from:
  - `GET /api/auth/csrf`
- Clients must include the returned token header on POST requests.

## Security headers

The backend sets baseline security headers on responses, including:

- `X-Content-Type-Options: nosniff`
- `Referrer-Policy: strict-origin-when-cross-origin`

## RBAC and authorization

- Admin routes under `/api/admin/**` and `/api/auth/admin/**` require role `SYSTEM_ADMIN`.
- Authenticated user routes return `401` when no session is present.
- Role-protected routes return `403` for authenticated users without required permissions.
- Connection visibility is user-scoped (`GET /api/datasources`) based on group-to-connection grants.
- Query execution requests (`POST /api/queries`) are denied with `403` when connection access is not granted.
- Deployments can require a query justification for non-read-only credential profiles and write-like scripts with
  `dwarvenpick.query.require-write-justification` or `DWARVENPICK_QUERY_REQUIRE_WRITE_JUSTIFICATION`.
- Justifications are trimmed, control characters are normalized, capped by `dwarvenpick.query.max-justification-length`,
  and stored with query status, history, and audit events.

## Audit events

Authentication, RBAC, and query governance actions produce audit events:

- local login success/failure
- LDAP login success/failure
- LDAP group sync membership changes
- logout
- admin password reset
- group create/update/member add/member remove
- connection access mapping create/update/delete
- query execution allowed/denied decisions
- query justification rejection for governed write-capable requests

Audit events are stored in the application database and pruned by `dwarvenpick.query.audit-retention-days`.

Query text is redacted from persisted query history and runtime records after
`dwarvenpick.query.query-text-redaction-days` days. The default is 7 days.

## Datasource network guard

Dwarvenpick validates datasource hosts when admins create or update managed datasources, when admins test connections,
when users validate SQL, and again before resolving a connection for asynchronous query execution. The guard can deny
host patterns and CIDR ranges, require allowlisted hosts or CIDRs, and reject private networks when configured to do so.
Loopback, link-local, any-local, and multicast addresses are always blocked when the guard is enabled.

IX deployments keep private networks allowed because approved data-platform endpoints resolve to internal RFC1918 or
cluster service addresses. They explicitly deny metadata ranges such as `169.254.0.0/16` and `fd00:ec2::254/128`,
plus hostname patterns like `localhost` and `metadata.google.internal`.

Config-managed datasource bootstrap can persist reviewed connections while DNS is temporarily unresolved at startup.
Runtime validation remains strict before validation, connection testing, and query execution.

Rejected datasource hosts on synchronous admin, connection-test, and validation paths return a generic `400` response.
If an existing datasource is blocked during asynchronous query execution, the execution is marked failed with a sanitized
error and audited with `reason=network_blocked`. If a deferred bootstrap host is still unresolved at execution time, the
execution is audited with `reason=network_unresolved`. Detailed rejected host and resolved-address context is kept in
backend logs for operator debugging.
