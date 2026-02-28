---
title: Deployment
parent: Operations
nav_order: 20
---

# Deployment

## Targets

- Docker (`deploy/docker`)
- Kubernetes Helm (`deploy/helm/dwarvenpick`)
- Bare metal systemd (`deploy/systemd/dwarvenpick.service`)

## Helm example values

Sample Helm deployments live under `deploy/helm/examples`:

- `values-local-auth.yaml`: local auth enabled, LDAP disabled
- `values-ldap-auth.yaml`: LDAP enabled, local auth disabled

## Runtime assumptions

- Java 21 runtime for backend
- Node/Nginx for frontend static bundle
- No metadata DB required (catalog, history, and audit data are stored in memory)
- Credential encryption key provided by environment or secret store

## Seeding sample connections

For local demos, the backend can seed a small set of sample connections on startup.

Disable in production:

- `DWARVENPICK_SEED_ENABLED=false` (default in Helm)

## Bootstrapping connections from a config file

For environments that want connections managed as code, the backend can bootstrap connections, credential profiles, and
(optionally) group access rules from a YAML file at startup:

- `DWARVENPICK_CONNECTIONS_CONFIG_PATH=/path/to/connections.yaml`

### Structured YAML format

```yaml
connections:
  - name: starrocks-adhoc-dev
    engine: STARROCKS
    host: dev.example.com
    port: 9030
    driverId: starrocks-mysql
    options:
      allowPublicKeyRetrieval: "true"
      serverTimezone: "UTC"
    credentialProfiles:
      read-only:
        username: svc_reader
        password: ${ENV:STARROCKS_RO_PASSWORD}
      read-write:
        username: svc_writer
        password: ${ENV:STARROCKS_RW_PASSWORD}
    access:
      - groupId: analytics-users
        credentialProfile: read-only
        canQuery: true
        canExport: false
        readOnly: true
```

### Environment interpolation

Any string value may reference environment variables using:

- `${ENV:VAR_NAME}` (required)
- `${ENV:VAR_NAME:-default}` (optional with default)

By default, the application fails fast if a referenced environment variable is missing. To allow missing vars (replaced
with an empty string), set:

- `DWARVENPICK_CONNECTIONS_FAIL_ON_MISSING_ENV=false`

## Credential encryption configuration

Connection passwords (credential profiles) are stored encrypted (AES-GCM). Configure the following runtime values:

- `DWARVENPICK_CREDENTIAL_MASTER_KEY`: required in production; do not commit to source control.
- `DWARVENPICK_CREDENTIAL_ACTIVE_KEY_ID`: key identifier used for new encryptions.

Helm values that map to these env vars:

- `.Values.credentials.activeKeyId`
- `.Values.credentials.masterKey.existingSecret` + `.Values.credentials.masterKey.key` (recommended)
- `.Values.credentials.masterKey.value` (local/dev only)

After key rotation, use the admin endpoint to re-encrypt all stored credential profiles:

```bash
curl -X POST \
  -H "X-XSRF-TOKEN: <token>" \
  --cookie "JSESSIONID=<session>" \
  http://localhost:8080/api/admin/datasource-management/credentials/reencrypt
```

Detailed rotation guidance: `docs/ops/credential-rotation.md`.

## LDAP setup

Configure LDAP only when directory authentication is required:

- `DWARVENPICK_AUTH_LDAP_ENABLED=true`
- `DWARVENPICK_AUTH_LDAP_URL=ldap://<host>:389` (or `ldaps://...`)
- `DWARVENPICK_AUTH_LDAP_BIND_DN=<bind-dn>`
- `DWARVENPICK_AUTH_LDAP_BIND_PASSWORD=<bind-password>`
- `DWARVENPICK_AUTH_LDAP_USER_SEARCH_BASE=<base-dn>`
- Optional group sync:
  - `DWARVENPICK_AUTH_LDAP_GROUP_SYNC_ENABLED=true`
  - `DWARVENPICK_AUTH_LDAP_GROUP_SYNC_GROUP_SEARCH_BASE=<group-base-dn>`
  - Optional role mapping:
    - `DWARVENPICK_AUTH_LDAP_SYSTEM_ADMIN_GROUPS=<comma-separated-group-ids>` (grants `SYSTEM_ADMIN` when any mapped group matches)

Login UX only supports `Local` and `LDAP` methods. The enabled set is exposed by `GET /api/auth/methods`.

When LDAP is enabled, local auth is disabled by default to avoid accidental backdoor access via seeded users. To keep local
auth enabled alongside LDAP (for example in local development), set:

- `DWARVENPICK_AUTH_LOCAL_ALLOW_WITH_LDAP=true`

## Local user administration

When local auth is enabled (`DWARVENPICK_AUTH_LOCAL_ENABLED=true`), `SYSTEM_ADMIN` users can manage local users in the Governance UI:

- create users with temporary or permanent passwords
- grant optional `SYSTEM_ADMIN` role during creation
- reset existing local passwords with temporary/permanent behavior

When local auth is disabled (LDAP-only), user creation/reset is blocked in UI and API (`/api/auth/admin/users*`).

## Persistent storage (drivers + TLS materials)

The backend uses `DWARVENPICK_EXTERNAL_DRIVERS_DIR` (default: `/opt/app/drivers`) as a writable state directory.

It stores:

- Uploaded and Maven-downloaded JDBC driver jars under `uploads/`
- Uploaded TLS certificates/keys and generated keystores/truststores under `tls/`

Kubernetes (Helm) recommendation:

- Enable the external drivers volume: `.Values.drivers.external.enabled=true`
- Back it with a PVC: set `.Values.drivers.external.createPvc=true` or provide `.Values.drivers.external.existingClaim=<pvc-name>`.
- Ensure it is writable (`.Values.drivers.external.readOnly=false`) if you plan to upload drivers or TLS materials from the UI.

Docker Compose:

- `deploy/docker/docker-compose.yml` mounts a named volume at `/opt/app/drivers` so uploaded drivers, Maven downloads, and TLS materials persist across restarts.

More details: `docs/ops/storage.md`.

## External JDBC drivers (Vertica)

Vertica is intentionally not bundled. Driver jars must be mounted at runtime.

### Kubernetes (Helm)

Configure these values:

- `.Values.drivers.external.enabled=true`
- `.Values.drivers.external.createPvc=true` (recommended for UI driver uploads)
- `.Values.drivers.external.mountPath` (default `/opt/app/drivers`)
- `.Values.drivers.external.existingClaim` (PVC containing driver jars)

If `existingClaim` is empty and `createPvc=false`, chart uses `emptyDir` (useful for local smoke tests only).

### Bare metal

Create a directory and place vendor jars there, then export:

```bash
export DWARVENPICK_EXTERNAL_DRIVERS_DIR=/opt/app/drivers
```

Ensure the application user can read files in that directory.

## Local Helm smoke test (Minikube)

Use this to validate chart rendering, install, and cleanup in a local cluster:

```bash
minikube start --driver=docker --install-addons=false --wait=apiserver
helm lint deploy/helm/dwarvenpick
helm upgrade --install dwarvenpick-test deploy/helm/dwarvenpick \
  --namespace dwarvenpick-test --create-namespace \
  --set credentials.masterKey.value=local-dev-master-key \
  --set drivers.external.enabled=false
kubectl get all -n dwarvenpick-test
helm uninstall dwarvenpick-test -n dwarvenpick-test
kubectl delete namespace dwarvenpick-test
minikube stop
```

Notes:

- The default image value (`ghcr.io/your-org/dwarvenpick-backend:latest`) is a placeholder and will not pull until replaced.
- Even with placeholder images, install/uninstall validates chart structure and Kubernetes resource wiring.
- For real Vertica connectivity in Kubernetes, mount the vendor JDBC jar(s) via a PVC and set `.Values.drivers.external.enabled=true`.

## Sizing guidance

Start with:

- 2 replicas (backend), HPA min 2 / max 6
- CPU request `250m`, memory request `512Mi`
- CPU limit `1000m`, memory limit `1Gi`
- Postgres managed separately with provisioned IOPS

Tune based on:

- `dwarvenpick_query_active{status="queued"}`
- query timeout/cancel rates
- pool saturation (`dwarvenpick_pool_active / dwarvenpick_pool_total`)
- p95 query latency under representative load (`docs/perf-testing.md`)
