---
title: Storage
parent: Operations
nav_order: 22
---

# Storage

`dwarvenpick` uses the application database for durable runtime state and a writable directory for file artifacts.

## Application database

The application database stores:

- HTTP sessions
- query history, active query status, buffered query results, and audit events
- snippets and scripts/resources
- local users, identity snapshots, RBAC groups, and access mappings
- admin-created connections and credential profile metadata
- connection pause state
- uploaded custom JDBC driver metadata

Use a shared PostgreSQL database for HA and production. The local embedded H2 database is intended for development.
PostgreSQL is also the coordination boundary for atomic actor, aggregate, and per-connection query admission across replicas; do not
run multiple backend replicas against H2.

Schema changes are managed by Flyway migrations. Existing deployments with pre-created state tables are baselined and
migrated forward on startup.

Legacy Resource Manager data in `scripts.json` is imported into the database on startup if the resource tables are empty.

Script/resource versions are retained according to:

- `dwarvenpick.resources.version-retention-days`
- `dwarvenpick.resources.max-versions-per-resource`

## File artifacts

Some features need a writable directory to persist artifacts:

- JDBC driver jars uploaded from the UI
- JDBC driver jars downloaded from Maven Central
- TLS/SSL materials (CA certificates, client certificates, client keys) and generated keystores/truststores

### External drivers directory

The backend uses `DWARVENPICK_EXTERNAL_DRIVERS_DIR` (default: `/opt/app/drivers`) as the root for these artifacts.

Directory layout:

- `uploads/<driver-id>/...`:
  - uploaded driver jars
  - Maven-downloaded driver jars (stored using the same mechanism as uploads)
- `tls/<connection-id>/...`:
  - `ca.pem`, `client.pem`, `client.key`
  - `truststore.p12`, `keystore.p12` (generated)

Notes:

- The application writes **no log files** to this directory by default. Backend logs go to stdout for Kubernetes-friendly collection. See `docs/ops/logging.md`.
- Driver uploads, Maven installs, and TLS certificate uploads are disabled by default. Enable artifact writes only when all backend replicas can access the same artifact storage, or run with a single backend instance.
- If `.Values.drivers.external.readOnly=true` in Helm, driver uploads and TLS uploads will fail (read-only mount).

## Docker Compose persistence

`deploy/docker/docker-compose.yml` mounts a named volume at `/opt/app/drivers` so file artifacts persist across restarts.

If you prefer a host bind mount (for easier inspection/backups during development), replace the volume with a host path, for example:

```yaml
services:
  backend:
    volumes:
      - ./data/drivers:/opt/app/drivers
```

Create the directory on the host before starting compose:

```bash
mkdir -p deploy/docker/data/drivers
```

## Kubernetes persistence (Helm)

To persist artifacts across pod restarts, mount a PVC at the external drivers directory.

Recommended values:

```yaml
drivers:
  external:
    enabled: true
    createPvc: true
    mountPath: /opt/app/drivers
    readOnly: false
```

Or provide your own PVC:

```yaml
drivers:
  external:
    enabled: true
    existingClaim: dwarvenpick-drivers
    mountPath: /opt/app/drivers
    readOnly: false
```
