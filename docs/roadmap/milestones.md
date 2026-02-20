# badgermole Roadmap Milestones

## Milestone 0: Repo + Foundation

- [x] Monorepo scaffold (`backend`, `frontend`, `deploy`, `docs`)
- [x] Gradle multi-module baseline
- [x] Dockerfiles + docker-compose
- [x] Helm chart scaffold
- [x] CI pipeline scaffold
- [x] Backend health endpoint
- [x] Frontend app scaffold

## Milestone 1: Schema + Core Services

- [ ] Flyway migrations for users/groups/datasources/audit/query history
- [ ] jOOQ model generation
- [ ] CRUD service stubs for users/groups/datasources
- [ ] Audit helper abstraction

## Milestone 2: Authentication

- [x] Local login/logout
- [x] LDAP login
- [x] Cookie session management + CSRF
- [x] Admin user lifecycle actions

## Milestone 3: RBAC + Access Control

- [x] Roles and group membership management
- [x] Group-to-datasource access mapping
- [x] Authorization enforcement for query endpoints
- [x] Integration tests for denied access

## Milestone 4: Datasource + Driver Registry

- [x] Datasource CRUD and test-connection
- [x] Encrypted credential storage
- [x] Driver registry (built-in + mounted jars)
- [x] Pooling per datasource/profile

## Milestone 5: SQL Editor MVP

- [x] Monaco integration
- [x] Multi-tab editor state
- [x] Datasource context per tab
- [x] Run/Cancel controls wiring

## Milestone 6: Query Engine

- [x] Execution lifecycle manager
- [x] Cursor-session pagination (next/prev)
- [x] Reliable cancellation
- [x] Timeout and safety limit enforcement

## Milestone 7: Result UX + Export

- [x] Virtualized result grid
- [x] CSV export options
- [x] Streaming export implementation
- [x] Export audit events

## Milestone 8: History + Audit UI

- [x] User query history panel
- [x] Admin audit view
- [x] Re-run from history
- [x] Retention and pruning jobs

## Milestone 9: Hardening + Ops

- [x] Production Helm tuning
- [x] Metrics and dashboards
- [x] Structured logs + correlation IDs
- [x] Readiness/liveness and sizing docs

## Milestone 10: Phase 2+ Polish

- [x] Schema browser + cache
- [x] Autocomplete
- [x] SQL formatting
- [x] Saved snippets
- [x] Read-only/read-write modes
- [ ] OIDC SSO
