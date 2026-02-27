---
title: Storage
parent: Operations
nav_order: 22
---

# Storage

`dwarvenpick` is designed to be stateless by default, but some features need a writable directory to persist artifacts:

- JDBC driver jars uploaded from the UI
- JDBC driver jars downloaded from Maven Central
- TLS/SSL materials (CA certificates, client certificates, client keys) and generated keystores/truststores

## External drivers directory

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
- If `.Values.drivers.external.readOnly=true` in Helm, driver uploads and TLS uploads will fail (read-only mount).

## Docker Compose persistence

`deploy/docker/docker-compose.yml` mounts a named volume at `/opt/app/drivers` so artifacts persist across restarts.

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

