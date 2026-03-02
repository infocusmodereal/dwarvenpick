---
title: Troubleshooting
nav_order: 80
---

# Troubleshooting

## UI is up but login fails

- Confirm the backend is healthy: `GET /api/health`
- Clear cookies for `localhost:3000` and retry.
- Check backend logs for Spring Session schema errors (for example `SPRING_SESSION` table missing).
  - If using PostgreSQL, ensure the DB user can create tables/indexes on first start (or pre-create the Spring Session tables).
  - As a temporary workaround (dev only), you can disable JDBC sessions with `SPRING_SESSION_STORE_TYPE=none`.

## Queries are queued for a long time

- Verify the selected connection is reachable (Connections page -> test connection).
- Check max runtime / concurrency limits in the access rule.
- For local dev: restart the stack with `docker compose ... up -d`.

## Query status events time out (reverse proxy)

`dwarvenpick` streams query status over Server-Sent Events (SSE) at `GET /api/queries/events`.

If you see `504`/timeouts behind a reverse proxy or ingress, ensure:

- proxy buffering is disabled for the SSE endpoint
- read timeouts are long enough for an open stream

## CSV export denied

Export is governed by access rules. Ensure your group has `can export` enabled for the connection.
