#!/usr/bin/env bash
#
# Require az cli 2.0.76+
# On  Mac: brew upgrade azure-cli
# az extension update --name aks-preview
# az feature register --name NodePublicIPPreview --namespace Microsoft.ContainerService
# az account set --subscription 72738c1b-8ae6-4f23-8531-5796fe866f2e
set -x

export REGISTRY_URL=strapdata.azurecr.io
export REGISTRY_SECRET_NAME=${REGISTRY_SECRET_NAME:-"strapregistry"}

# $1 = $RESOURCE_GROUP_NAME
function create_resource_group() {
    az group create -l westeurope -n $1
}

# $1 = $RESOURCE_GROUP_NAME
function destroy_resource_group() {
    az group delete -n $1
}

# $1 = $RESOURCE_GROUP_NAME
# $1 = vnet name
function create_vnet0() {
    az network vnet create --name vnet0 -g $1 --address-prefix 10.0.0.0/17 --subnet-name subnet0 --subnet-prefix 10.0.0.0/24
}


function create-acr-rbac() {
    eval $(az ad sp create-for-rbac \
    --scopes /subscriptions/$SUBSCRIPTION_ID/resourcegroups/$ACR_RESOURCE_GROUP/providers/Microsoft.ContainerRegistry/registries/$ACR_NAME \
    --role Reader \
    --name $SERVICE_PRINCIPAL_NAME | jq -r '"export SPN_PW=\(.password) && export SPN_CLIENT_ID=\(.appId)"')
}

function create-acr-secret() {
    kubectl create secret docker-registry $REGISTRY_SECRET_NAME --docker-server=https://strapdata.azurecr.io --docker-username="$SPN_CLIENT_ID" --docker-password="$SPN_PW" --docker-email="vroyer@strapdata.com"
}



# AKS zone availability (require VM Scale Set) does not allow to add public IPs on nodes because of the standard LB.
# So, keep AvailabilitySet deployment with no LB unless you deploy one.
# $1 = k8s cluster IDX
function create_aks_cluster() {
     az network vnet subnet create -g $RESOURCE_GROUP_NAME --vnet-name vnet0 -n "subnet$1" --address-prefixes 10.0.$1.0/24
     local B3=$((64+($1 -1)*16))

     az aks create --name "${K8S_CLUSTER_NAME}${1}" \
                  --resource-group $RESOURCE_GROUP_NAME \
                  --ssh-key-value $SSH_PUBKEY \
                  --network-plugin azure \
                  --docker-bridge-address "192.168.0.1/24" \
                  --service-cidr "10.0.$B3.0/22" \
                  --dns-service-ip "10.0.$B3.10" \
                  --vnet-subnet-id $(az network vnet subnet show -g $RESOURCE_GROUP_NAME --vnet-name vnet0 -n "subnet$1" | jq -r '.id') \
                  --node-count 1 \
                  --node-vm-size Standard_D4_v3 \
                  --vm-set-type AvailabilitySet \
                  --output table
#                  --attach-acr "$ACR_ID"
#                   --load-balancer-sku basic
#                  --load-balancer-managed-outbound-ip-count 0 \

    kubectl create clusterrolebinding kubernetes-dashboard -n kube-system --clusterrole=cluster-admin --serviceaccount=kube-system:kubernetes-dashboard

     use_k8s_cluster $1
}

# $1 = k8s cluster IDX
# $2 = node index
function add_public_ip() {
   AKS_RG_NAME=$(az resource show --namespace Microsoft.ContainerService --resource-type managedClusters -g $RESOURCE_GROUP_NAME -n "${K8S_CLUSTER_NAME}${1}" | jq -r .properties.nodeResourceGroup)
   AKS_NODE=$(az vm list --resource-group MC_koptest2_kube2_westeurope | jq -r ".[$2] .name")
   az network nic ip-config list --nic-name "${AKS_NODE::-2}-nic-2" -g $AKS_RG_NAME
   az network public-ip create -g $RESOURCE_GROUP_NAME --name "${K8S_CLUSTER_NAME}${1}-ip$2" --dns-name "${K8S_CLUSTER_NAME}${1}-pub${2}" --sku Standard
   az network nic ip-config update -g $AKS_RG_NAME --nic-name "${AKS_NODE::-2}-nic-2" --name ipconfig1 --public-ip-address "${K8S_CLUSTER_NAME}${1}-ip$2"
}

# require AKS with VM ScaleSet
# $1 = k8s cluster IDX
function add_nodepool2() {
    az aks nodepool add --cluster-name "${K8S_CLUSTER_NAME}${1}" \
                  --resource-group $RESOURCE_GROUP_NAME \
                  --name nodepool2 \
                  --node-vm-size Standard_B2S \
                  --node-count 1
}


# $1 = RESOURCE_GROUP_NAME
# $2 = K8S_CLUSTER_NAME
function delete_aks_cluster() {
    az aks delete  --name "${2}" --resource-group $1
}

# $1 = RESOURCE_GROUP_NAME
# $2 = K8S_CLUSTER_NAME
# $2 = namespace
function use_aks_cluster() {
	 az aks get-credentials --name "${2}" --resource-group $1 --output table
	 kubectl config use-context $3
	 kubectl config set-context $1 --cluster=${2}
}