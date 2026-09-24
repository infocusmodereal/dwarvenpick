---
title: Authentication
nav_order: 6
---

# Authentication

`dwarvenpick` supports multiple authentication methods.

## Local

- Username/password records are stored in the application database (passwords are hashed).
- Intended for development, small deployments, and break-glass access.
- Local user administration is available to SYSTEM_ADMINs when enabled.

## LDAP

- Directory-backed authentication.
- User creation/reset is not supported in the UI when LDAP-only mode is enabled.
- Secure transport is explicit: use `START_TLS` with an `ldap://` URL or `LDAPS` with an `ldaps://` URL.
- `AUTO` preserves pre-0.14 compatibility by selecting `PLAIN` for `ldap://` and `LDAPS` for `ldaps://`; governed deployments should set the mode explicitly.
- Certificate and hostname verification use the JVM trust configuration and cannot be disabled through Dwarvenpick.

## OIDC (Keycloak / JumpCloud)

- OIDC-based single sign-on (SSO) using Spring Security OAuth2 login.
- Intended for enterprise identity providers (Keycloak, JumpCloud, Okta, etc).
- When OIDC is enabled, Local auth is disabled by default to avoid accidental backdoor access via seeded users.

Minimal settings:

- `DWARVENPICK_AUTH_OIDC_ENABLED=true`
- `DWARVENPICK_AUTH_OIDC_ISSUER_URI=https://<idp>/realms/<realm>` (Keycloak example)
- `DWARVENPICK_AUTH_OIDC_CLIENT_ID=<client-id>`
- `DWARVENPICK_AUTH_OIDC_CLIENT_SECRET=<client-secret>`

Optional settings:

- `DWARVENPICK_AUTH_OIDC_REDIRECT_URI_TEMPLATE={baseUrl}/login/oauth2/code/{registrationId}`
- `DWARVENPICK_AUTH_OIDC_SCOPES=openid,profile,email`
- `DWARVENPICK_AUTH_OIDC_SYSTEM_ADMIN_GROUPS=<group1>,<group2>` (comma-separated)
- `DWARVENPICK_AUTH_LOCAL_ALLOW_WITH_OIDC=true` (recommended only for local dev / break-glass)

Notes:

- By default, `dwarvenpick` auto-derives the `authorization`, `token`, and `jwk-set` endpoints for Keycloak issuers
  (issuer URLs containing `/realms/`).
- `DWARVENPICK_AUTH_OIDC_USER_INFO_URI` is optional. If omitted, `dwarvenpick` will not call the UserInfo endpoint and
  will rely on ID token claims instead. Ensure your IdP includes any required claims (for example `groups`) in the ID
  token.
- For non-Keycloak issuers (or custom setups), you can override endpoints via:
  - `DWARVENPICK_AUTH_OIDC_AUTHORIZATION_URI`
  - `DWARVENPICK_AUTH_OIDC_TOKEN_URI`
  - `DWARVENPICK_AUTH_OIDC_JWK_SET_URI`
  - `DWARVENPICK_AUTH_OIDC_USER_INFO_URI`

## Choosing methods

The backend exposes enabled methods via:

- `GET /api/auth/methods`

## Sessions

`dwarvenpick` uses server-side HTTP sessions (`JSESSIONID`) with an HTTP-only cookie.

Sessions are JDBC-backed via Spring Session (`spring-session-jdbc`).

If you run with the default embedded H2 metadata DB, a backend redeploy/restart may still invalidate active logins
depending on how the local process is started. For multi-replica deployments and redeploy-safe logins, use a shared
Postgres metadata DB.

### Persistent sessions (recommended for HA)

Enable a shared JDBC-backed session store (Spring Session) by setting:

- `SPRING_SESSION_STORE_TYPE=jdbc`
- `SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<db>`
- `SPRING_DATASOURCE_USERNAME=<user>`
- `SPRING_DATASOURCE_PASSWORD=<password>`

Flyway migrations create and upgrade the required Spring Session tables (`SPRING_SESSION`,
`SPRING_SESSION_ATTRIBUTES`) for PostgreSQL and H2. Ensure your DB user has permission to create tables and indexes.

When running behind HTTPS, also set:

- `DWARVENPICK_SESSION_COOKIE_SECURE=true`

### Helm configuration

In Helm deployments, auth methods are controlled via environment variables (or `.Values.env.*`):

- `DWARVENPICK_AUTH_LOCAL_ENABLED=true|false`
- `DWARVENPICK_AUTH_LDAP_ENABLED=true|false`
- `DWARVENPICK_AUTH_LDAP_TRANSPORT=AUTO|PLAIN|START_TLS|LDAPS`
- `DWARVENPICK_AUTH_OIDC_ENABLED=true|false`

See sample Helm values under `deploy/helm/examples`.

## Supplemental LDAP user groups

Operators can grant named LDAP users additional internal groups without changing directory memberships:

```yaml
dwarvenpick:
  auth:
    ldap:
      user-group-mappings:
        - username: alice.example
          groups: [temporary-deals-readers]
```

Mappings match the authenticated directory username exactly, ignoring case. They are applied only after successful LDAP authentication and combined with directory groups on every login. They do not create LDAP groups or local accounts. Wildcards, empty mappings, and system administrator groups are rejected at startup. Administrator roles continue to derive only from directory groups.

Grant these internal groups explicit connection permissions using the normal connection catalog, preferably a read-only database credential with export disabled. Membership changes appear in the existing LDAP group-sync and login audit events. Configuration defaults to an empty list.

For temporary access, remove the mapping and its dedicated connection grants when the exception ends. Membership removal takes effect at the next login; existing sessions retain their principal until logout or expiration, so remove connection grants as well for immediate access revocation. This setting does not implement automatic expiry.
