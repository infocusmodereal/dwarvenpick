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

By default, sessions are stored in memory, which means:

- a backend redeploy/restart will invalidate active logins
- multi-replica deployments may require sticky sessions unless a shared session store is configured

### Persistent sessions (recommended for HA)

Enable a shared JDBC-backed session store (Spring Session) by setting:

- `SPRING_SESSION_STORE_TYPE=jdbc`
- `SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<db>`
- `SPRING_DATASOURCE_USERNAME=<user>`
- `SPRING_DATASOURCE_PASSWORD=<password>`

When running behind HTTPS, also set:

- `DWARVENPICK_SESSION_COOKIE_SECURE=true`

### Helm configuration

In Helm deployments, auth methods are controlled via environment variables (or `.Values.env.*`):

- `DWARVENPICK_AUTH_LOCAL_ENABLED=true|false`
- `DWARVENPICK_AUTH_LDAP_ENABLED=true|false`

See sample Helm values under `deploy/helm/examples`.
