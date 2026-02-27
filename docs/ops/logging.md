---
title: Logging
parent: Operations
nav_order: 25
---

# Logging

`dwarvenpick` is designed for container and Kubernetes deployments. Backend logs are emitted to **stdout** (console) by default so your platform logging stack can collect them.

## Log format

The backend uses structured JSON logs (one event per line) and includes a `correlationId` field for tracing request flows.

## Configure log level

Spring Boot supports configuring log levels via environment variables.

Common examples:

- `LOGGING_LEVEL_ROOT=INFO`
- `LOGGING_LEVEL_COM_DWARVENPICK=DEBUG`

### Helm

Use `extraEnv` in the Helm chart:

```yaml
extraEnv:
  LOGGING_LEVEL_ROOT: INFO
  LOGGING_LEVEL_COM_DWARVENPICK: DEBUG
```

### Docker

Use `docker compose` environment overrides (or your container runtime's env configuration) and read logs from `docker logs` / `kubectl logs`.

