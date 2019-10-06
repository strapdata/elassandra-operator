# Strapkop HELM

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

## Confure Azure Registry secret

Create a Service Principal to connect to the azure registry:

```bash
az ad sp create-for-rbac \
  --scopes /subscriptions/72738c1b-8ae6-4f23-8531-5796fe866f2e/resourcegroups/strapcloud.com/providers/Microsoft.ContainerRegistry/registries/strapdata \
  --role Contributor \
  --name strapreg
{
  "appId": "37dfee37-1c3d-4fed-8863-e35241acece7",
  "displayName": "strapreg",
  "name": "http://strapreg",
  "password": "4ccb75bc-6833-4cd4-88ad-3084d8f0d1af",
  "tenant": "566af820-2f8c-45ac-b975-647d2647b277"
}
```

Docker login with the SP and generated password:

```bash
docker login strapdata.azurecr.io -u <appId> -p <password>
```

Create a K8S secret with azure registry service principal:

```bash
kubectl create secret docker-registry azurecr \
  --docker-server strapdata.azurecr.io \
  --docker-email vroyer@strapdata.com \
  --docker-username=strapreg \
  --docker-password 4ccb75bc-6833-4cd4-88ad-3084d8f0d1af
```
## Publish for Strapkube

run ./publish.sh to upload HELM package on the Azure blogstore and in the Azure HELM repo.

## Deploy Strapkop

    helm install --name kop --namespace default elassandra-operator

## Deploy a Datacenter

    helm install --name cl1-dc1 --namespace default elassandra-datacenter