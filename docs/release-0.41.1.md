---
title: Release 0.41.1
nav_exclude: true
---

# Release 0.41.1

Security maintenance release for the complete 0.41.0 feature set.

- Upgrade to Spring Boot 4.0.9 and Spring Framework 7.0.10, including fixes for CVE-2026-47884 and CVE-2026-47890.
- Upgrade Jackson 2 to the Boot-managed 2.21.7 release and Guava to 33.7.2-jre.
- Align Kotlin, Gradle, servlet container, session, Flyway and test modules with Spring Boot 4. Keep Java 21 as the runtime baseline.
- Retain the Jackson 2 compatibility module for persisted JSON and existing application integrations while using compatibility defaults for HTTP JSON serialization.

The [0.41.0 upgrade requirements](release-0.41.0.md#upgrade-requirements) still apply when upgrading from 0.40.x: stop all backend writers, back up the database, migrate and verify external identity links before reopening access. This patch adds no database migration. Deploy frontend and backend together; restore the database and previous images together if rolling back across the 0.41.0 migrations.

See the [Spring Boot migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) for the dependency and configuration changes.
