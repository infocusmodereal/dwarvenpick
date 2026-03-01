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

## Datasource integration tests

Query execution is covered by Testcontainers-based integration tests (Postgres, MySQL, Trino) and requires Docker:

```bash
./gradlew :backend:app:test --tests com.dwarvenpick.app.QueryExecutionManagerContainerTests
```

## Versioning and releases

`dwarvenpick` uses Git tags for release versions.

- Dev builds default to `0.2.2-SNAPSHOT`.
- Release builds use the pushed tag name (for example `v0.2.2` becomes version `0.2.2`).

To cut a release:

1. Create a tag:

   ```bash
   git tag v0.2.2
   git push origin v0.2.2
   ```

2. GitHub Actions runs the `Release` workflow and publishes a GitHub Release with:
   - versioned backend jar
   - versioned Helm chart package

To build a versioned jar locally without tagging:

```bash
DWARVENPICK_VERSION=0.2.2 ./gradlew :backend:app:bootJar
```
