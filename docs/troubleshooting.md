---
title: Troubleshooting
nav_order: 80
---

# Troubleshooting

## UI is up but login fails

- Confirm the backend is healthy: `GET /api/health`
- Clear cookies for `localhost:3000` and retry.

## Queries are queued for a long time

- Verify the selected connection is reachable (Connections page -> test connection).
- Check max runtime / concurrency limits in the access rule.
- For local dev: restart the stack with `docker compose ... up -d`.

## CSV export denied

Export is governed by access rules. Ensure your group has `can export` enabled for the connection.

