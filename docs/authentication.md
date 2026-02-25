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

### Helm configuration

In Helm deployments, auth methods are controlled via environment variables (or `.Values.env.*`):

- `DWARVENPICK_AUTH_LOCAL_ENABLED=true|false`
- `DWARVENPICK_AUTH_LDAP_ENABLED=true|false`

See sample Helm values under `deploy/helm/examples`.
