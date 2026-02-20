# Deployment Notes

## Targets

- Docker (`deploy/docker`)
- Kubernetes Helm (`deploy/helm/badgermole`)
- Bare metal systemd (`deploy/systemd/badgermole.service`)

## Runtime assumptions

- Java 21 runtime for backend
- Node/Nginx for frontend static bundle
- PostgreSQL metadata database
- Credential encryption key provided by environment or secret store

## Credential encryption configuration

Datasource passwords are stored encrypted (AES-GCM). Configure the following runtime values:

- `BADGERMOLE_CREDENTIAL_MASTER_KEY`: required in production; do not commit to source control.
- `BADGERMOLE_CREDENTIAL_ACTIVE_KEY_ID`: key identifier used for new encryptions.

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

- `BADGERMOLE_AUTH_LDAP_ENABLED=true`
- `BADGERMOLE_AUTH_LDAP_URL=ldap://<host>:389` (or `ldaps://...`)
- `BADGERMOLE_AUTH_LDAP_BIND_DN=<bind-dn>`
- `BADGERMOLE_AUTH_LDAP_BIND_PASSWORD=<bind-password>`
- `BADGERMOLE_AUTH_LDAP_USER_SEARCH_BASE=<base-dn>`
- Optional group sync:
  - `BADGERMOLE_AUTH_LDAP_GROUP_SYNC_ENABLED=true`
  - `BADGERMOLE_AUTH_LDAP_GROUP_SYNC_GROUP_SEARCH_BASE=<group-base-dn>`

Login UX only supports `Local` and `LDAP` methods. The enabled set is exposed by `GET /api/auth/methods`.

## External JDBC drivers (Vertica)

Vertica is intentionally not bundled. Driver jars must be mounted at runtime.

### Kubernetes (Helm)

Configure these values:

- `.Values.drivers.external.enabled=true`
- `.Values.drivers.external.mountPath` (default `/opt/app/drivers`)
- `.Values.drivers.external.existingClaim` (PVC containing driver jars)

If `existingClaim` is empty, chart uses `emptyDir` (useful for local smoke tests only).

### Bare metal

Create a directory and place vendor jars there, then export:

```bash
export BADGERMOLE_EXTERNAL_DRIVERS_DIR=/opt/app/drivers
```

Ensure the application user can read files in that directory.

## Local Helm smoke test (Minikube)

Use this to validate chart rendering, install, and cleanup in a local cluster:

```bash
minikube start --driver=docker --install-addons=false --wait=apiserver
helm lint deploy/helm/badgermole
helm upgrade --install badgermole-test deploy/helm/badgermole \
  --namespace badgermole-test --create-namespace \
  --set credentials.masterKey.value=local-dev-master-key \
  --set drivers.external.enabled=false
kubectl get all -n badgermole-test
helm uninstall badgermole-test -n badgermole-test
kubectl delete namespace badgermole-test
minikube stop
```

Notes:

- The default image value (`ghcr.io/your-org/badgermole-backend:latest`) is a placeholder and will not pull until replaced.
- Even with placeholder images, install/uninstall validates chart structure and Kubernetes resource wiring.
- For real Vertica connectivity in Kubernetes, mount the vendor JDBC jar(s) via a PVC and set `.Values.drivers.external.enabled=true`.

## Metadata DB backup and restore

The metadata database is PostgreSQL and should be backed up independently from application pods.

Example backup:

```bash
pg_dump --format=custom --no-owner --no-privileges \
  --dbname="$SPRING_DATASOURCE_URL" \
  --username="$SPRING_DATASOURCE_USERNAME" \
  --file=badgermole-meta-$(date +%Y%m%d%H%M%S).dump
```

Example restore to a new environment:

```bash
createdb badgermole_restore
pg_restore --clean --if-exists --no-owner \
  --dbname=badgermole_restore \
  badgermole-meta-YYYYMMDDHHMMSS.dump
```

After restore:

- validate Flyway/schema compatibility with the running app version
- rotate `BADGERMOLE_CREDENTIAL_MASTER_KEY` only with a planned re-encryption run
- verify admin login and datasource catalog integrity before opening traffic

## Sizing guidance

Start with:

- 2 replicas (backend), HPA min 2 / max 6
- CPU request `250m`, memory request `512Mi`
- CPU limit `1000m`, memory limit `1Gi`
- Postgres managed separately with provisioned IOPS

Tune based on:

- `badgermole_query_active{status="queued"}`
- query timeout/cancel rates
- pool saturation (`badgermole_pool_active / badgermole_pool_total`)
- p95 query latency under representative load (`docs/perf-testing.md`)
