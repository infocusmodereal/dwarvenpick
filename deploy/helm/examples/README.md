# Helm examples

This folder contains sample `values.yaml` files for `deploy/helm/dwarvenpick`.

## Secrets

### Credential master key secret

`dwarvenpick` encrypts stored connection credential profiles with a master key. Store that key in a Kubernetes Secret.

Create a secret with key `master-key` (or override `credentials.masterKey.key`).

Example:

```bash
kubectl create secret generic dwarvenpick-credentials \
  --from-literal=master-key='replace-with-random-32+bytes'
```

## External driver storage (PVC)

To support uploading JDBC driver jars from the UI, enable the external drivers directory and back it with a PVC:

```yaml
drivers:
  external:
    enabled: true
    createPvc: true
```

If your cluster requires a specific storage class, set `drivers.external.pvc.storageClassName`.

## Install

Local auth example:

```bash
helm upgrade --install dwarvenpick deploy/helm/dwarvenpick \
  --namespace dwarvenpick --create-namespace \
  -f deploy/helm/examples/values-local-auth.yaml
```

LDAP auth example:

```bash
helm upgrade --install dwarvenpick deploy/helm/dwarvenpick \
  --namespace dwarvenpick --create-namespace \
  -f deploy/helm/examples/values-ldap-auth.yaml
```
