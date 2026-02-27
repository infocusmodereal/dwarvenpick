---
title: Connections
nav_order: 4
---

# Connections

Connections define how `dwarvenpick` reaches your databases through JDBC.

## Connection naming rules

Connection ids must follow:

- Lowercase letters, numbers, dots (`.`), and hyphens (`-`)
- Must start with a letter

Examples:

- `postgresql-core`
- `starrocks-warehouse`
- `mysql.analytics`

## Create a connection

1. Open **Connections**
2. Select **Create connection**
3. Fill the **Quick setup** section
4. Expand advanced sections only when needed:
   - Connection (JDBC URL, options)
   - Driver (built-in or uploaded jar)
   - Pooling
   - TLS

## Uploading driver jars (Kubernetes)

If you plan to upload JDBC driver jars from the UI, the backend needs a writable, persistent external drivers directory.

For Helm deployments:

- set `.Values.drivers.external.enabled=true`
- set `.Values.drivers.external.createPvc=true` (or provide `.Values.drivers.external.existingClaim`)

## Installing drivers from Maven Central

System admins can also install supported JDBC driver versions directly from the UI. Downloads are stored in the external drivers directory.

Settings:

- `DWARVENPICK_DRIVERS_MAVEN_ENABLED` (default: `true`)
- `DWARVENPICK_DRIVERS_MAVEN_REPOSITORY_URL` (default: `https://repo1.maven.org/maven2/`)
- `DWARVENPICK_DRIVERS_MAVEN_MAX_JAR_SIZE_MB` (default: `50`)

Helm chart values:

- `.Values.drivers.maven.enabled`
- `.Values.drivers.maven.repositoryUrl`
- `.Values.drivers.maven.maxJarSizeMb`

The UI shows the Maven coordinates (`groupId` + `artifactId`) and driver class for each preset so operators can verify exactly what is being downloaded.

## TLS and SSL certificates

`dwarvenpick` supports TLS for all connections. In the UI, TLS controls whether transport encryption is required and how the server certificate is validated.

Optionally, you can also upload certificates for TLS verification and mutual TLS:

- **CA certificate (PEM)**: used to verify the database server certificate.
- **Client certificate (PEM)** + **client private key (PEM)**: used for mutual TLS (mTLS) authentication.

Notes:

- The client private key must be an **unencrypted PKCS#8 PEM** (`BEGIN PRIVATE KEY`). PKCS#1 (`BEGIN RSA PRIVATE KEY`) and encrypted private keys are rejected with a clear error.
- Uploaded TLS materials are stored on the backend under `${DWARVENPICK_EXTERNAL_DRIVERS_DIR}/tls/<connection-id>/`.
- In Kubernetes, make sure the external drivers directory is writable and backed by a PVC if you want TLS materials (and driver jars) to survive pod restarts.

Convert a PKCS#1 RSA key to PKCS#8 (unencrypted):

```bash
openssl pkcs8 -topk8 -nocrypt -in client.key -out client.pkcs8.key
```

## Pooling

Connections can use pooled JDBC connections for better concurrency. Pool sizing and timeouts are configurable per connection.
