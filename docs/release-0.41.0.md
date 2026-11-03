---
title: Release 0.41.0
nav_exclude: true
---

# Release 0.41.0

- Immutable account IDs and explicit verified external-identity linking prevent account collisions.
- Membership updates and script revision writes are transactional; optimistic revision checks prevent lost edits.
- Query retention protects active executions and result access; execution audit events preserve authorization evidence.
- Explorer controls are more compact, search sits above the tree, and Query History shows the submitting user with compact actions and bounded connection names.

## Upgrade requirements

Back up the database and stop every backend writer before migration. Deploy backend and frontend together. V6 consolidates group memberships and invalidates persisted sessions, including remembered devices. Existing LDAP/OIDC accounts need independently verified identity links. External-only deployments must complete the [offline bootstrap](authentication.md#external-only-upgrade-bootstrap) before reopening access. Do not infer account ownership from matching usernames.

Older script-editing clients must supply `expectedRevision`; reopen existing browser tabs after upgrade. LDAP uses a stable configured identity issuer rather than its transport URL. Rollback after migration requires restoring the database backup and the previous application images together.
