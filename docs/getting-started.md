---
title: Getting started
nav_order: 2
---

# Getting started

## Run locally with Docker Compose

```bash
docker compose -f deploy/docker/docker-compose.yml up -d --build
```

Then open:

- UI: `http://localhost:3000`
- Backend health: `http://localhost:3000/api/health`

Seeded local development users (dev only, do not reuse in production):

- `admin / Admin1234!` (roles: `SYSTEM_ADMIN`, `USER`)
- `analyst / Analyst123!` (role: `USER`)

The local stack also includes a Keycloak instance for OIDC SSO:

- Keycloak admin: `http://localhost:8081` (`admin / admin`)
- Realm: `dwarvenpick`
- Seeded SSO user: `oidc-admin / Admin1234!`

## First query

1. Go to **Workbench**
2. Pick a connection from the connection dropdown (for example `postgresql-core`).
3. Run:

```sql
SELECT 1;
```

## Seeded local databases

The default local stack includes:

- PostgreSQL sample source: `localhost:5432` (`dwarvenpick` / `dwarvenpick`)
- MySQL sample source: `localhost:3306` (`readonly` / `readonly`)
- MariaDB sample source: `localhost:3307` (`readonly` / `readonly`)
- Vertica sample source: `localhost:5433` (`dbadmin` / `dwarvenpick`)
- StarRocks sample source: `localhost:9030` (`readonly` / `readonly`)
- Trino sample source: `localhost:8088` (user `trino`, no password)

## Stop

```bash
docker compose -f deploy/docker/docker-compose.yml down
```
