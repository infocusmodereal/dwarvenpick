# dwarvenpick

[![CI](https://github.com/infocusmodereal/dwarvenpick/actions/workflows/ci.yml/badge.svg)](https://github.com/infocusmodereal/dwarvenpick/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)

`dwarvenpick` is a web-based SQL workbench focused on secure, audited ad-hoc querying across multiple data platforms.

## Features

- Tabbed SQL editor (Monaco) with syntax highlighting and keyboard shortcuts.
- Results grid with pagination, sorting, and CSV export.
- Connection catalog and connection management UI.
- Governance:
  - Groups and access rules (per-connection query/export/read-only controls).
  - Local user management for development and small deployments.
- Audit trail:
  - User query history.
  - Admin audit events.

## Supported engines

Current focus is JDBC-backed SQL engines:

- PostgreSQL
- MySQL
- MariaDB
- StarRocks (via MySQL protocol)

## Quick start (local, Docker Compose)

Prerequisites:

- Docker Desktop (or compatible Docker engine) with Compose support.

Start the full stack:

```bash
docker compose -f deploy/docker/docker-compose.yml up -d --build
```

Open:

- UI: `http://localhost:3000`
- Backend health: `http://localhost:3000/api/health`

Seeded local development users (dev only, do not reuse in production):

- `admin / Admin1234!` (roles: `SYSTEM_ADMIN`, `USER`)
- `analyst / Analyst123!` (role: `USER`)

Seeded databases in local compose:

- PostgreSQL metadata DB: `localhost:5432`, db `dwarvenpick`, user `dwarvenpick`, password `dwarvenpick`
- MySQL sample source: `localhost:3306`, db `orders`, user `readonly`, password `readonly`
- MariaDB sample source: `localhost:3307`, db `warehouse`, user `readonly`, password `readonly`
- StarRocks sample source: `localhost:9030`, db `warehouse`, user `readonly`, password `readonly`

The sample datasets include both transactional-style tables and analytical-style tables/views to exercise query/explain behavior.

## Development

Prerequisites:

- Java 21 (backend)
- Node.js 22 + npm (frontend)

Backend:

```bash
./gradlew :backend:app:bootRun
```

Frontend:

```bash
cd frontend
npm ci
npm run dev
```

By default the Vite dev server runs on `http://localhost:5173` and proxies `/api` and `/actuator` to `http://localhost:8080`.

## Repository layout

- `backend/`: Kotlin + Spring Boot backend (multi-module Gradle build)
- `frontend/`: React + TypeScript web application
- `deploy/`: Docker and Helm deployment assets
- `docs/roadmap/`: Product spec and milestone roadmap

## Documentation

- Roadmap: `docs/roadmap/`
- Contributing: `CONTRIBUTING.md`
- Agent conventions: `AGENTS.md`

## License

Apache-2.0. See `LICENSE`.
