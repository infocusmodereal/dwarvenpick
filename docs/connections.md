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

## Pooling

Connections can use pooled JDBC connections for better concurrency. Pool sizing and timeouts are configurable per connection.
