# Security Baseline (Milestones 2-3)

## Session and authentication

- Authentication uses server-side HTTP sessions (`JSESSIONID`) with HTTP-only cookies.
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
- Datasource visibility is user-scoped (`GET /api/datasources`) based on group-to-datasource grants.
- Query execution requests (`POST /api/queries`) are denied with `403` when datasource access is not granted.

## Audit events

Authentication, RBAC, and query governance actions produce audit events:

- local login success/failure
- LDAP login success/failure
- LDAP group sync membership changes
- logout
- admin password reset
- group create/update/member add/member remove
- datasource access mapping create/update/delete
- query execution allowed/denied decisions
