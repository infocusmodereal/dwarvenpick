# Deployment Notes (Skeleton)

## Targets

- Docker (`deploy/docker`)
- Kubernetes Helm (`deploy/helm/badgermole`)
- Bare metal systemd (`deploy/systemd/badgermole.service`)

## Runtime assumptions

- Java 21 runtime for backend
- Node/Nginx for frontend static bundle
- PostgreSQL metadata database

## Security baseline for future milestones

- TLS terminated at ingress/reverse proxy
- Secure cookie + CSRF strategy
- Credential encryption at rest (AES-GCM) planned for datasource credential service
