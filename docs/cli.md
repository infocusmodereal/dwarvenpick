---
title: CLI
nav_order: 7
---

# CLI

`dwarvenpick` includes a Node.js CLI package under `cli/` for running queries from a terminal.

The CLI uses the same backend API as the web UI. It authenticates with a session cookie and CSRF token, then submits
queries through the normal query execution endpoint. RBAC, credential profile resolution, read-only enforcement, query
limits, and audit logging all remain server-side.

## Supported authentication

The CLI supports password login with:

- Local auth
- LDAP auth

OIDC is browser-based in dwarvenpick, so the CLI does not perform OIDC password login. If your deployment is OIDC-only,
enable a secure LDAP or Local break-glass path before using the CLI.

## Install from a release tarball

Download the `dwarvenpick-cli-<version>.tgz` asset and `SHA256SUMS` from the matching GitHub Release, then verify and
install it:

```bash
sha256sum -c SHA256SUMS --ignore-missing
npm install -g ./dwarvenpick-cli-<version>.tgz
dwarvenpick --version
```

The CLI version should match the app release version shown by `/api/version` and the web UI version button.

## Install from source for development

```bash
cd cli
npm link
```

Or run directly:

```bash
node cli/bin/dwarvenpick.js --help
```

## Environment variables

```bash
export DWARVENPICK_URL=https://dwarvenpick.example.com
export DWARVENPICK_AUTH=ldap
export DWARVENPICK_USERNAME=<username>
export DWARVENPICK_PASSWORD=<password>
export DWARVENPICK_CONNECTION=<connection-id>
export DWARVENPICK_JUSTIFICATION=<change-ticket-or-reason>
```

`DWARVENPICK_AUTH=auto` prefers LDAP when it is enabled and falls back to Local auth.

## List connections

```bash
dwarvenpick connections --format table
```

Output formats:

- `table`
- `json`
- `csv`

Table and CSV output contain one row per effective credential profile available to the signed-in user. Each row shows
read-only/write capability, exact export permission, effective row/runtime/concurrency limits, justification mode, and
the independent elevated System Health flag. JSON keeps the same information as structured
`credentialProfilePolicies` data. Policies are user-specific RBAC results and must not be reused between accounts.

## Run queries

```bash
dwarvenpick query \
  --connection postgresql-core \
  --sql 'SELECT 1 AS value;' \
  --format table
```

Use a SQL file:

```bash
dwarvenpick run \
  --connection starrocks-dev-adhoc \
  --file ./query.sql \
  --format csv \
  --output ./results.csv
```

Use a credential profile override:

```bash
dwarvenpick query \
  --connection mariadb-dev-viper2 \
  --credential-profile read-only \
  --sql 'SELECT COUNT(*) FROM warehouse.customers;'
```

Use a governed write-capable profile:

```bash
dwarvenpick query \
  --connection mariadb-dev-viper2 \
  --credential-profile read-write \
  --justification 'TOPS-123 maintenance window' \
  --sql 'UPDATE warehouse.job_control SET enabled = 0 WHERE job_id = 42;'
```

Run a multi-statement script:

```bash
dwarvenpick query \
  --connection postgresql-core \
  --script \
  --transaction TRANSACTION \
  --file ./maintenance.sql
```

## Operational notes

- Progress messages are written to stderr.
- Query output is written to stdout unless `--output` is set.
- `--page-size` controls results pagination and is capped at `1000`.
- `--timeout` and `--poll-interval` accept durations like `500ms`, `5s`, or `2m`.
- On timeout or `Ctrl-C`, the CLI requests backend cancellation before returning a non-zero exit.
- Failed or canceled queries return a non-zero exit code.

## Package README

See `cli/README.md` for the full command reference and local development commands.
