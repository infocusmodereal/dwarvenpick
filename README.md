<p align="center">
  <img src="frontend/public/dwarvenpick-mark.svg" width="140" alt="dwarvenpick logo" />
</p>

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

- PostgreSQL sample source: `localhost:5432`, db `dwarvenpick`, user `dwarvenpick`, password `dwarvenpick`
- MySQL sample source: `localhost:3306`, db `orders`, user `readonly`, password `readonly`
- MariaDB sample source: `localhost:3307`, db `warehouse`, user `readonly`, password `readonly`
- StarRocks sample source: `localhost:9030`, db `warehouse`, user `readonly`, password `readonly`

The sample datasets include both transactional-style tables and analytical-style tables/views to exercise query/explain behavior.

## Persistence (drivers + TLS materials)

The backend uses `DWARVENPICK_EXTERNAL_DRIVERS_DIR` (default: `/opt/app/drivers`) as a writable state directory for:

- JDBC driver jars uploaded from the UI
- JDBC driver jars downloaded from Maven Central
- Uploaded TLS/SSL certificates and generated keystores/truststores

Docker Compose mounts a named volume at `/opt/app/drivers` so these artifacts persist across restarts.

For Kubernetes (Helm), enable the external drivers volume and back it with a PVC:

- `.Values.drivers.external.enabled=true`
- `.Values.drivers.external.createPvc=true` (or provide `.Values.drivers.external.existingClaim`)

## Observability

- Logs: backend logs are emitted to stdout as JSON. Configure levels with `LOGGING_LEVEL_ROOT` and `LOGGING_LEVEL_COM_DWARVENPICK`.
- Metrics: `GET /actuator/prometheus` (toggle with `DWARVENPICK_METRICS_PROMETHEUS_ENABLED`).

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

- `backend/`: Kotlin + Spring Boot backend (Gradle)
- `frontend/`: React + TypeScript web application
- `deploy/`: Docker and Helm deployment assets
- `docs/`: User and operator documentation (published to GitHub Pages)

## Documentation

- User docs (GitHub Pages): https://infocusmodereal.github.io/dwarvenpick/
- Contributing: `CONTRIBUTING.md`
- Agent conventions: `AGENTS.md`

## Releases

Releases are cut from Git tags (`vX.Y.Z`). Pushing a tag triggers the `Release` GitHub Actions workflow, which publishes a GitHub Release with a versioned backend jar and Helm chart package.

## License

Apache-2.0. See `LICENSE`.
