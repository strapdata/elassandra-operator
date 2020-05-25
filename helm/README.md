# Elassandra Operator HELM

## Package HELM charts

```bash
./gradlew :helm:helmPackageElassandraDatacenterChart
./gradlew :helm:helmPackageElassandraOperatorChart
```

## Upload charts to azure blobstore

```bash
./gradlew :helm:uploadElassandraDatacenter
./gradlew :helm:uploadElassandraOperator
```

## Generate an operator self-signed certificate

```bash
openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
  -keyout elassandra-operator.key -out elassandra-operator.crt -extensions san -config \
  <(echo "[req]"; 
    echo distinguished_name=req; 
    echo "[san]"; 
    echo subjectAltName=DNS:elassandra-operator.default.svc
    ) \
  -subj "/CN=localhost"
```

Deploy it as a K8s secret 

```bash
kubectl -n default create secret tls elassandra-operator --cert=elassandra-operator.crt --key=elassandra-operator.key
```