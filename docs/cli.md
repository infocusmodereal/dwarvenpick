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

## Install from source

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
- Failed or canceled queries return a non-zero exit code.

## Package README

See `cli/README.md` for the full command reference and local development commands.
