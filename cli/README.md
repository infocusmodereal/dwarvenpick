# dwarvenpick CLI

Command line client for authenticated dwarvenpick query execution.

The CLI uses the same HTTP API, session cookie, CSRF token, RBAC rules, query limits, audit trail, and credential profile
resolution as the web UI. It supports password login with Local and LDAP auth. OIDC is browser-based and is not used for
CLI password login.

## Requirements

- Node.js 22 or newer
- A running dwarvenpick backend reachable from your terminal
- Local or LDAP authentication enabled on the target instance

## Install from a release tarball

Download the `dwarvenpick-cli-<version>.tgz` asset and `SHA256SUMS` from the matching GitHub Release, then verify and
install it:

```bash
sha256sum -c SHA256SUMS --ignore-missing
npm install -g ./dwarvenpick-cli-<version>.tgz
dwarvenpick --version
```

The CLI version should match the app release version shown by `/api/version` and the web UI version button.

## Install from a checkout for development

```bash
cd dwarvenpick/cli
npm link
```

Or run without linking:

```bash
node ./bin/dwarvenpick.js --help
```

## Configure

Every option can be passed as a flag or as an environment variable:

```bash
export DWARVENPICK_URL=http://localhost:3000
export DWARVENPICK_AUTH=ldap
export DWARVENPICK_USERNAME=ivan.torres
export DWARVENPICK_PASSWORD='...'
export DWARVENPICK_CONNECTION=starrocks-dev-adhoc
export DWARVENPICK_JUSTIFICATION='TOPS-123 maintenance window'
```

Supported auth values:

- `auto`: prefer LDAP when enabled, otherwise Local
- `ldap`: force LDAP password login
- `local`: force Local password login

## Commands

### Check login

```bash
dwarvenpick login --auth local --username admin --password 'Admin1234!'
```

### List permitted connections

```bash
dwarvenpick connections --format table
dwarvenpick connections --format json
dwarvenpick connections --format csv
```

### Run a query

```bash
dwarvenpick query \
  --connection postgresql-core \
  --sql 'SELECT 1 AS value;' \
  --format table
```

Read SQL from a file:

```bash
dwarvenpick run \
  --connection starrocks-dev-adhoc \
  --file ./query.sql \
  --format csv \
  --output ./results.csv
```

Use a specific credential profile:

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

Run a script:

```bash
dwarvenpick query \
  --connection postgresql-core \
  --script \
  --transaction TRANSACTION \
  --file ./maintenance.sql
```

## Query behavior

- The CLI submits the query and polls until it reaches a terminal state.
- Results are fetched through the paginated results API and streamed to the selected output as pages arrive.
- `--page-size` controls each results request and is capped at `1000`.
- Output formats are `table`, `json`, and `csv`.
- `table` output is rendered per page with repeated headers; prefer `json` or `csv` for large result exports.
- Progress messages go to stderr so stdout remains safe for piping.
- On timeout or `Ctrl-C`, the CLI requests backend cancellation before returning a non-zero exit.

## Security notes

- Prefer `DWARVENPICK_PASSWORD` or a short-lived shell secret over putting passwords in shell history.
- The CLI never bypasses RBAC. The backend still resolves the effective credential profile and enforces read-only rules,
  row limits, runtime limits, concurrency limits, audit logging, and export permissions.
- Deployments can require `--justification` or `DWARVENPICK_JUSTIFICATION` for non-read-only credential profiles and
  write-like scripts.
- OIDC providers such as Keycloak or JumpCloud are supported by the web UI. Use Local or LDAP for CLI password login.

## Development

```bash
npm test
npm pack --dry-run
```
