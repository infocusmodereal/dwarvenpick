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

- [ ] Local login/logout
- [ ] LDAP login
- [ ] Cookie session management + CSRF
- [ ] Admin user lifecycle actions

## Milestone 3: RBAC + Access Control

- [ ] Roles and group membership management
- [ ] Group-to-datasource access mapping
- [ ] Authorization enforcement for query endpoints
- [ ] Integration tests for denied access

## Milestone 4: Datasource + Driver Registry

- [ ] Datasource CRUD and test-connection
- [ ] Encrypted credential storage
- [ ] Driver registry (built-in + mounted jars)
- [ ] Pooling per datasource/profile

## Milestone 5: SQL Editor MVP

- [ ] Monaco integration
- [ ] Multi-tab editor state
- [ ] Datasource context per tab
- [ ] Run/Cancel controls wiring

## Milestone 6: Query Engine

- [ ] Execution lifecycle manager
- [ ] Cursor-session pagination (next/prev)
- [ ] Reliable cancellation
- [ ] Timeout and safety limit enforcement

## Milestone 7: Result UX + Export

- [ ] Virtualized result grid
- [ ] CSV export options
- [ ] Streaming export implementation
- [ ] Export audit events

## Milestone 8: History + Audit UI

- [ ] User query history panel
- [ ] Admin audit view
- [ ] Re-run from history
- [ ] Retention and pruning jobs

## Milestone 9: Hardening + Ops

- [ ] Production Helm tuning
- [ ] Metrics and dashboards
- [ ] Structured logs + correlation IDs
- [ ] Readiness/liveness and sizing docs

## Milestone 10: Phase 2+ Polish

- [ ] Schema browser + cache
- [ ] Autocomplete
- [ ] SQL formatting
- [ ] Saved snippets
- [ ] Read-only/read-write modes
- [ ] OIDC SSO
