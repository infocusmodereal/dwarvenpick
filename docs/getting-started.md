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
- StarRocks sample source: `localhost:9030` (`readonly` / `readonly`)

## Stop

```bash
docker compose -f deploy/docker/docker-compose.yml down
```
