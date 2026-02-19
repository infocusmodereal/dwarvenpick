# badgermole (OpenSQL Workbench)

`badgermole` is an enterprise-focused, web-based SQL workbench for secure ad-hoc querying across multiple data platforms.

## Repository layout

- `backend/`: Kotlin + Spring Boot multi-module backend
- `frontend/`: React + TypeScript web application
- `deploy/`: Docker and Helm deployment assets
- `docs/roadmap/`: Product spec and milestone roadmap

## Quick start (skeleton)

### Backend

```bash
./gradlew :backend:app:bootRun
```

Health endpoint:

- `GET http://localhost:8080/api/health`

### Frontend

```bash
cd frontend
npm install
npm run dev
```

Default local UI URL:

- `http://localhost:5173`

### Local stack (Docker Compose)

```bash
docker compose -f deploy/docker/docker-compose.yml up --build
```

## License

Apache-2.0. See `LICENSE`.
