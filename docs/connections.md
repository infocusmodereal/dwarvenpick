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

## Pooling

Connections can use pooled JDBC connections for better concurrency. Pool sizing and timeouts are configurable per connection.
