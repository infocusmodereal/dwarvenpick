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

## Stable accounts and external identity linking

Accounts have an immutable `user_id`; usernames remain the public handle for existing
APIs and historical ownership. OIDC login resolves `(provider, issuer, subject)`.
LDAP resolves a stable directory identifier (`dwarvenpick.auth.ldap.identity-issuer`,
default `ldap-directory`) and the authenticated distinguished name. Configure it
through `DWARVENPICK_AUTH_LDAP_IDENTITY_ISSUER`. Keep the
identifier unchanged when moving the same directory to another host or TLS transport;
use a different identifier when replacing the directory with a different identity authority.
A changed LDAP DN or identity issuer requires a new explicit link.
A matching username alone never links identities or converts a local account.
Local password reset is restricted to local accounts.

V6 deliberately invalidates all persisted sessions, including remember-device sessions,
because the serialized principal format changes. Everyone must sign in again.
For external-only installations, follow the offline bootstrap below before reopening
traffic; no authenticated administrator session or local login is required.
Otherwise, existing external accounts can be linked by a system administrator before
their first login after this upgrade. Use `POST /api/auth/admin/users/{username}/identities`
with an authenticated administrator session, a CSRF token and this JSON body:

```json
{"provider":"OIDC","issuer":"https://identity.example/realms/team","subject":"verified-provider-subject"}
```

Verify the issuer and subject in the identity provider before linking. For LDAP,
use `provider: LDAP`, the configured identity issuer as issuer and the authenticated
DN as subject. The account's provider must match. Links are unique and cannot be
silently reassigned; conflicts return HTTP 409. Successful links are audited.
The endpoint does not enable disabled users or permit automatic cross-provider conversion.

### External-only upgrade bootstrap

1. Back up the application database and stop all writers. Independently verify at
   least one existing external administrator's provider, issuer and immutable subject
   against the authoritative directory/IdP. Do not infer ownership from a matching
   username, email or a failed login attempt.
2. Run the migrations while ingress remains closed. Using the application's database
   administrator connection, explicitly link the verified legacy account. Replace all
   example values below with that verified identity:

   ```sql
   BEGIN;
   INSERT INTO app_external_identities (provider, issuer, subject, user_id)
   SELECT provider, 'https://identity.example/realms/team', 'verified-provider-subject', user_id
   FROM app_users
   WHERE username = 'existing-admin' AND provider = 'OIDC' AND enabled = TRUE
     AND user_id LIKE 'legacy:%'
     AND NOT EXISTS (
       SELECT 1 FROM app_external_identities links WHERE links.user_id = app_users.user_id
     )
   RETURNING provider, issuer, subject, user_id;
   COMMIT;
   ```

   Execute through RETURNING first. Commit only after exactly one expected row is
   returned; otherwise ROLLBACK and investigate. For LDAP substitute `LDAP`, the configured stable identity issuer and the verified
   DN. Unique identity constraints reject reassignment. Record the operator's verified
   mapping and approval in the deployment change record; direct SQL does not emit an
   application audit event.
3. Confirm the external provider still maps that administrator to SYSTEM_ADMIN.
   Reopen traffic and sign in through the provider, then use the protected API above
   for remaining verified accounts (or pre-link each via the same offline procedure).
   Do not enable local auth or seed default credentials to recover access.

Conflicting LDAP/OIDC login attempts record issuer and subject in restricted admin
audit events to aid diagnosis, not as proof of account ownership. These identifiers
follow audit retention and should not be exposed in public logs.

Group authorization and administrative membership lists now read `app_user_groups`.
External login replaces that account's memberships with its mapped provider groups,
as before. Manual grants to external accounts therefore last until the next sync.
Authenticated requests reload account permissions; revoked memberships do not remain
active merely because an older session still contains them.
