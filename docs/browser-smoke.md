# Workbench browser smoke

The browser smoke validates the governed workbench path through the visible UI: login, connection selection, a fixed read-only query, result paging, current-page sort, CSV behavior, query history refresh, and logout. It writes aggregate evidence only. Credentials, cookies, tokens, SQL result cells, CSV contents, traces, screenshots, videos, and Playwright DOM error context are never persisted.

## Local full-stack run

Install the pinned frontend dependencies and Chromium once:

```bash
cd frontend
npm ci
npx playwright install chromium
cd ..
```

Run the smoke. The runner owns a dedicated Compose project, publishes only frontend/backend, waits for real HTTP readiness, and always removes its containers and named volumes:

```bash
scripts/browser/run-workbench-smoke.sh
```

Local mode only accepts a loopback `BASE_URL`. It uses the seeded local admin account, `mariadb-mart`, a fixed read-only query against seeded data, and a 10-row page size. The sanitized report is written to `build/reports/browser-smoke/workbench-browser-smoke.json`.

Frontend and backend default to host ports `3000` and `8080`. When either is occupied, set `BROWSER_SMOKE_FRONTEND_PORT` and `BROWSER_SMOKE_BACKEND_PORT`; set `BASE_URL` only when it differs from the selected frontend port.

The default `BROWSER_SMOKE_BUILD=true` rebuilds the application images. Use `BROWSER_SMOKE_BUILD=false` only to repeat the browser flow against images already built from the same checkout. The runner injects the package version, Git SHA/ref, local image tag, and a clean/dirty build tag into `/api/version` so the evidence identifies what was exercised.

The Compose override resets published ports and global container names for every service in the base stack. When a service is added to `deploy/docker/docker-compose.yml`, add it to the browser-smoke override before running this workflow.

## Approved dev run

Dev mode is fail-closed. It requires an explicit HTTPS allowlist, opt-in, approved credentials, a governed datasource, and an explicit export expectation. `allowed` requires a real download; `denied` requires visible failure feedback and no download. The SQL is fixed to `SELECT 1 AS browser_smoke;`; arbitrary SQL cannot be supplied. The run creates a normal query-history and audit entry in dev.

```bash
BROWSER_SMOKE_MODE=dev \
BROWSER_SMOKE_ALLOW_REMOTE=true \
BASE_URL=https://approved-dwarvenpick-dev.example.com \
BROWSER_SMOKE_ALLOWED_TARGETS=approved-dwarvenpick-dev.example.com \
BROWSER_SMOKE_USER="$APPROVED_USER" \
BROWSER_SMOKE_PASSWORD="$APPROVED_PASSWORD" \
BROWSER_SMOKE_DATASOURCE=approved-read-only-datasource \
BROWSER_SMOKE_CREDENTIAL_PROFILE=read-only \
BROWSER_SMOKE_EXPECT_EXPORT=allowed \
scripts/browser/run-workbench-smoke.sh
```

Omit `BROWSER_SMOKE_CREDENTIAL_PROFILE` for users who do not have the administrator-only profile override control; RBAC will select their governed profile.

Do not pass credentials as command arguments or enable shell tracing. Load them into the environment from the approved secret source, and unset them after the run. Allowed targets use exact `host` or `host:port` entries; URL substrings, user information, paths, trailing-dot hostnames, and unapproved ports are rejected.

## Evidence contract

`schemaVersion: 1` covers the current field names and meanings. Increment it for renamed or removed fields, type changes, or semantic changes that would break a consumer. Adding an optional field does not require a version bump. Public release identity from `/api/version` binds the result to the tested build.

CI runs the target-validator unit tests and compiles/lists the Playwright suite on every change. The resource-intensive full Compose run remains a required manual release check.
