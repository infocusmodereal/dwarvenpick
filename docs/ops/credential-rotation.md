---
title: Credential encryption rotation
parent: Operations
nav_order: 10
---

# Credential encryption rotation

This project encrypts datasource credential passwords at rest using AES-GCM.

## Runtime settings

- `DWARVENPICK_CREDENTIAL_MASTER_KEY`: current master key material.
- `DWARVENPICK_CREDENTIAL_ACTIVE_KEY_ID`: logical key id stored with each encrypted credential.

## Rotation workflow

1. Provision a new master key in your secret manager.
2. Deploy the application with:
   - new `DWARVENPICK_CREDENTIAL_MASTER_KEY`
   - new `DWARVENPICK_CREDENTIAL_ACTIVE_KEY_ID` (for example `v2`)
3. Run credential re-encryption:
   - `POST /api/admin/datasource-management/credentials/reencrypt`
4. Verify response reports all expected credential profiles updated.
5. Validate datasource connection tests from admin UI/API for critical datasources.
6. Decommission old key material after verification.

## Notes

- The master key is never persisted in DB tables and must not be logged.
- Re-encryption also evicts active datasource pools so new connections use freshly decrypted secrets.
- Keep the re-encryption call restricted to `SYSTEM_ADMIN`.
