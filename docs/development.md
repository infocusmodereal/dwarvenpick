---
title: Development
nav_order: 70
has_children: true
---

# Development

Contributor documentation for developing and testing `dwarvenpick`.

## Local checks

Backend:

```bash
./gradlew clean ktlintCheck test
```

Frontend:

```bash
cd frontend
npm ci
npm run lint
npm run format:check
npm run test
npm run build
```

## LDAP integration tests

LDAP authentication is covered by a Testcontainers-based integration test suite and requires Docker:

```bash
./gradlew :backend:app:test --tests com.dwarvenpick.app.auth.LdapAuthContainerTests
```

## Versioning and releases

`dwarvenpick` uses Git tags for release versions.

- Dev builds default to `0.1.0-SNAPSHOT`.
- Release builds use the pushed tag name (for example `v0.2.0` becomes version `0.2.0`).

To cut a release:

1. Create a tag:

   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```

2. GitHub Actions runs the `Release` workflow and publishes a GitHub Release with:
   - versioned backend jar
   - versioned Helm chart package

To build a versioned jar locally without tagging:

```bash
DWARVENPICK_VERSION=0.2.0 ./gradlew :backend:app:bootJar
```
