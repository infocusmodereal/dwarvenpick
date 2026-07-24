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
  - can export, scoped to the exact credential profile used by the query execution
  - read-only
  - limits (max rows, max runtime, concurrency)

An export grant for one credential profile does not authorize exporting results produced with another profile on the
same connection. Operators should provision export access explicitly for every profile that requires it.

## Read-only enforcement

`readOnly: true` enables Dwarvenpick's conservative, engine-aware lexical guard. It rejects write/admin statements,
data-modifying CTEs, executing `EXPLAIN` variants, multi-statement bypasses, and write-like `SELECT INTO` forms before
query admission.

This guard is defense in depth, not a semantic SQL sandbox. SQL functions and engine extensions can have side effects
that no lexical classifier can prove safe. Every read-only access rule must therefore use a database credential whose
grants are independently read-only. Never map `readOnly: true` to a credential with database write, DDL, file-write, or
administrative privileges.

## Local users

When Local auth is enabled, SYSTEM_ADMINs can:

- create local users (temporary or permanent password)
- reset passwords
- optionally grant SYSTEM_ADMIN

When LDAP-only mode is enabled, user creation and password resets are intentionally disabled.
The Users admin menu is also hidden in the UI to avoid presenting an empty local-user table.
