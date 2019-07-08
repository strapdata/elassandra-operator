#!/bin/bash

# Publish HELM chart in Azure Registry and upload to blobstore for strapkube usage.

set -x

export RESOURCE_GROUP=${RESOURCE_GROUP:-repo.strapdata.com}
export AZURE_STORAGE_ACCOUNT=strapdata
export AZURE_STORAGE_CONTAINER=enterprise
export AZURE_STORAGE_ACCESS_KEY=$(az storage account keys list --account-name ${AZURE_STORAGE_ACCOUNT} --resource-group ${RESOURCE_GROUP} --output json | jq -r '.[0].value')

# $1 = filename
# $2 = container (default = AZURE_STORAGE_CONTAINER)
upload_file() {
   az storage blob upload --file $1 --name $(basename $1) --container-name ${2:-"$AZURE_STORAGE_CONTAINER"} --account-key $AZURE_STORAGE_ACCESS_KEY
}

helm package elassandra-operator;
az acr helm push --force elassandra-operator-*.tgz -n strapdata
upload_file elassandra-operator-0.1.0.tgz charts

helm package elassandra-datacenter;
az acr helm push --force elassandra-datacenter-*.tgz -n strapdata
upload_file elassandra-datacenter-0.1.0.tgz charts