./gradlew :helm:helmPackageElassandraOperatorChart
./gradlew :helm:helmPackageElassandraOperatorChart
az acr helm delete --name strapdata elassandra-operator -y
az acr helm delete --name strapdata elassandra-datacenter -y
az acr helm push --name strapdata  helm/build/helm/charts/elassandra-datacenter-0.3.0.tgz
az acr helm push --name strapdata  helm/build/helm/charts/elassandra-operator-0.3.0.tgz
helm repo remove strapdata && az acr helm repo add -n strapdata
