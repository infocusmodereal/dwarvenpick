# badgermole (OpenSQL Workbench)

`badgermole` is an enterprise-focused, web-based SQL workbench for secure ad-hoc querying across multiple data platforms.

## Repository layout

- `backend/`: Kotlin + Spring Boot multi-module backend
- `frontend/`: React + TypeScript web application
- `deploy/`: Docker and Helm deployment assets
- `docs/roadmap/`: Product spec and milestone roadmap

## Developer prerequisites

- Java 21 (for backend Gradle/Spring Boot tasks)
- Node.js 22 + npm (for frontend dev/build/test)
- Docker + Docker Compose (optional, for local full-stack runs)

## Quick start (skeleton)

### Backend

```bash
./gradlew :backend:app:bootRun
```

Health endpoint:

- `GET http://localhost:8080/api/health`
- `GET http://localhost:8080/actuator/health`
- `GET http://localhost:8080/api/version`

Authentication endpoints:

- `GET http://localhost:8080/api/auth/csrf`
- `POST http://localhost:8080/api/auth/login`
- `POST http://localhost:8080/api/auth/ldap/login`
- `GET http://localhost:8080/api/auth/me`
- `POST http://localhost:8080/api/auth/logout`
- `POST http://localhost:8080/api/auth/admin/users/{username}/reset-password` (admin only)

Default local development users:

- `admin / Admin123!` (roles: `ADMIN`, `USER`)
- `analyst / Analyst123!` (role: `USER`)
- `disabled.user / Disabled123!` (disabled account, login blocked)

### Frontend

```bash
cd frontend
npm install
npm run dev
```

Default local UI URL:

- `http://localhost:5173`
- Dev server proxies `/api` and `/actuator` requests to `http://localhost:8080`.

### Local stack (Docker Compose)

```bash
docker compose -f deploy/docker/docker-compose.yml up --build
```

## License

Apache-2.0. See `LICENSE`.
