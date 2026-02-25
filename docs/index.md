---
title: Home
nav_order: 1
---

<p align="center">
  <img src="{{ site.baseurl }}/assets/dwarvenpick-mark.svg" width="120" alt="dwarvenpick logo" />
</p>

# dwarvenpick

`dwarvenpick` is a web-based SQL workbench focused on secure, audited ad-hoc querying across multiple data platforms.

## Quick start (local)

1. Start the local stack:

   ```bash
   docker compose -f deploy/docker/docker-compose.yml up -d --build
   ```

2. Open the UI: `http://localhost:3000`
3. Sign in with a seeded dev user:
   - `admin / Admin1234!` (SYSTEM_ADMIN)
   - `analyst / Analyst123!` (USER)

## Next steps

- [Getting started](getting-started.md)
- [Workbench](workbench.md)
- [Connections](connections.md)
- [Governance](governance.md)
- [Authentication](authentication.md)
