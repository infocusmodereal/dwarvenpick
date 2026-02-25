---
title: Governance
nav_order: 5
---

# Governance

Governance controls which users can see and query which connections.

## Concepts

- **Users**: accounts that can sign in (Local or LDAP).
- **Groups**: collections of users.
- **Access rules**: grants between a group and a connection, including controls:
  - can query
  - can export
  - read-only
  - limits (max rows, max runtime, concurrency)

## Local users

When Local auth is enabled, SYSTEM_ADMINs can:

- create local users (temporary or permanent password)
- reset passwords
- optionally grant SYSTEM_ADMIN

When LDAP-only mode is enabled, user creation and password resets are intentionally disabled.

