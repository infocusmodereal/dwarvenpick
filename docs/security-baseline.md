# Security Baseline (Milestone 2)

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

## Audit events

Authentication and password-management actions produce audit events:

- local login success/failure
- LDAP login success/failure
- LDAP group sync membership changes
- logout
- admin password reset
