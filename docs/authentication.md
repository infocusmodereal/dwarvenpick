---
title: Authentication
nav_order: 6
---

# Authentication

`dwarvenpick` supports multiple authentication methods.

## Local

- Username/password stored in memory (passwords are hashed).
- Intended for development, small deployments, and break-glass access.
- Local user administration is available to SYSTEM_ADMINs when enabled.

## LDAP

- Directory-backed authentication.
- User creation/reset is not supported in the UI when LDAP-only mode is enabled.

## Choosing methods

The backend exposes enabled methods via:

- `GET /api/auth/methods`

## Sessions

`dwarvenpick` uses server-side HTTP sessions (`JSESSIONID`) with an HTTP-only cookie.

Sessions are JDBC-backed via Spring Session (`spring-session-jdbc`).

If you run with the default embedded H2 metadata DB, a backend redeploy/restart will invalidate active logins. For
multi-replica deployments and redeploy-safe logins, use a shared Postgres metadata DB.

### Persistent sessions (recommended for HA)

Enable a shared JDBC-backed session store (Spring Session) by setting:

- `SPRING_SESSION_STORE_TYPE=jdbc`
- `SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<db>`
- `SPRING_DATASOURCE_USERNAME=<user>`
- `SPRING_DATASOURCE_PASSWORD=<password>`

On first start, `dwarvenpick` attempts to create the required Spring Session tables (`SPRING_SESSION`,
`SPRING_SESSION_ATTRIBUTES`) automatically for PostgreSQL and H2. Ensure your DB user has permission to create tables and
indexes (or pre-create them yourself).

When running behind HTTPS, also set:

- `DWARVENPICK_SESSION_COOKIE_SECURE=true`

### Helm configuration

In Helm deployments, auth methods are controlled via environment variables (or `.Values.env.*`):

- `DWARVENPICK_AUTH_LOCAL_ENABLED=true|false`
- `DWARVENPICK_AUTH_LDAP_ENABLED=true|false`

See sample Helm values under `deploy/helm/examples`.
