---
title: Runbooks
parent: Operations
nav_order: 40
---

# Runbooks

## 1) LDAP unavailable

Symptoms:
- LDAP login failures (`401`), local login still works (if enabled)
- health endpoint may show LDAP contributor issues in details

Actions:
1. Validate LDAP URL/bind settings in runtime config.
2. Verify network path and DNS from app runtime.
3. Temporarily route users to Local auth if operational policy allows.
4. Restore LDAP and validate with `/api/auth/ldap/login`.

## 2) Stuck query executions

Symptoms:
- queued/running executions not progressing
- high `dwarvenpick_query_active{status="running"}` for sustained period

Actions:
1. Inspect query history and audit events for affected connection/user.
2. Cancel stuck executions from UI/API.
3. Verify connection connectivity and pool saturation.
4. Confirm cleanup scheduler is active and retention cleanup logs appear.
5. If service restart is required, graceful shutdown will cancel remaining in-flight queries.
