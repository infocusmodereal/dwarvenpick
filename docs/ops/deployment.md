# Deployment Notes (Skeleton)

## Targets

- Docker (`deploy/docker`)
- Kubernetes Helm (`deploy/helm/badgermole`)
- Bare metal systemd (`deploy/systemd/badgermole.service`)

## Runtime assumptions

- Java 21 runtime for backend
- Node/Nginx for frontend static bundle
- PostgreSQL metadata database

## Local Helm smoke test (Minikube)

Use this to validate chart rendering, install, and cleanup in a local cluster:

```bash
minikube start --driver=docker --install-addons=false --wait=apiserver
helm lint deploy/helm/badgermole
helm upgrade --install badgermole-test deploy/helm/badgermole \
  --namespace badgermole-test --create-namespace
kubectl get all -n badgermole-test
helm uninstall badgermole-test -n badgermole-test
kubectl delete namespace badgermole-test
minikube stop
```

Notes:

- The default image value (`ghcr.io/your-org/badgermole-backend:latest`) is a placeholder and will not pull until replaced.
- Even with placeholder images, install/uninstall validates chart structure and Kubernetes resource wiring.

## Security baseline for future milestones

- TLS terminated at ingress/reverse proxy
- Secure cookie + CSRF strategy
- Credential encryption at rest (AES-GCM) planned for datasource credential service
