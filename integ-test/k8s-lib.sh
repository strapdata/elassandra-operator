#!/usr/bin/env bash
#
# Require az cli 2.0.76+
# On  Mac: brew upgrade azure-cli
# az extension update --name aks-preview
# az feature register --name NodePublicIPPreview --namespace Microsoft.ContainerService
# az account set --subscription 72738c1b-8ae6-4f23-8531-5796fe866f2e
set -x

HELM_REPO=../helm/src/main/helm
REGISTRY_SECRET_NAME="strapregistry"
ELASSANDRA_OPERATOR_TAG="6.2.3.22-SNAPSHOT"

function create_resource_group() {
    az group create -l westeurope -n $RESOURCE_GROUP_NAME
}

function destroy_resource_group() {
    az group delete -n $RESOURCE_GROUP_NAME
}

# $1 = vnet name
function create_vnet() {
    az network vnet create --name vnet0 -g $RESOURCE_GROUP_NAME --address-prefix 10.0.0.0/17 --subnet-name subnet0 --subnet-prefix 10.0.0.0/24
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

function nodes_zone() {
    kubectl get nodes -o wide -L failure-domain.beta.kubernetes.io/zone
}

# $1 = k8s cluster IDX
function destroy_aks_cluster() {
    az aks delete  --name "${K8S_CLUSTER_NAME}${1}" --resource-group $RESOURCE_GROUP_NAME
}

# $1 = kube index
function use_k8s_cluster() {
	 az aks get-credentials --name "${K8S_CLUSTER_NAME}${1}" --resource-group $RESOURCE_GROUP_NAME --output table
	 kubectl config use-context $NAMESPACE
	 kubectl config set-context $RESOURCE_GROUP_NAME --cluster=${K8S_CLUSTER_NAME}${1}
}


function init_helm() {
	 helm init
	 kubectl -n kube-system get po || helm init
	 kubectl create serviceaccount --namespace kube-system tiller
	 kubectl create clusterrolebinding tiller-cluster-rule --clusterrole=cluster-admin --serviceaccount=kube-system:tiller
	 kubectl patch deploy --namespace kube-system tiller-deploy -p '{"spec":{"template":{"spec":{"serviceAccount":"tiller"}}}}'
	 helm init --wait
}

# deploy the elassandra operator in $1 = namespace
function install_elassandra_operator() {
    helm install --namespace ${1:-default} --name strapkop \
    --set image.pullSecrets[0]="$REGISTRY_SECRET_NAME" \
    --set image.tag="$OPERATOR_TAG" \
    $HELM_REPO/elassandra-operator
}

# Deploy single node cluster in namespace=cluster_name
# $1 = cluster name
# $2 = datacenter name
function install_singlenode_elassandra_datacenter() {
    helm install --namespace $1 --name ${1:-"cl1"}-${2:-"dc1"} \
    --set image.pullSecrets[0]=$REGISTRY_SECRET_NAME \
    --set image.tag="$OPERATOR_TAG" \
    --set reaper.enabled=false \
    --set kibana.enabled=false \
    --set replicas=1 \
    --wait \
    $HELM_REPO/elassandra-datacenter
    echo "Datacenter $2 deployed in namespace $1"
}

function deploy_traefik_acme() {
	echo "Deploying traefik proxy with domain=${1:-$DNS_DOMAIN}"
	helm install $HELM_DEBUG --name traefik --namespace kube-system \
		--set rbac.enabled=true,debug.enabled=true \
		--set ssl.enabled=true,ssl.enforced=true \
		--set acme.enabled=true,acme.email="vroyer@strapdata.com",acme.storage="acme.json" \
		--set acme.logging=true,acme.staging=false \
		--set acme.challengeType="dns-01" \
		--set acme.caServer="https://acme-v02.api.letsencrypt.org/directory" \
		--set acme.dnsProvider.name="azure" \
		--set acme.dnsProvider.azure.AZURE_SUBSCRIPTION_ID="72738c1b-8ae6-4f23-8531-5796fe866f2e" \
		--set acme.dnsProvider.azure.AZURE_RESOURCE_GROUP="strapcloud.com" \
		--set acme.dnsProvider.azure.AZURE_CLIENT_ID="55aa320e-f341-4db8-8d3b-e28d1a41cb67" \
		--set acme.dnsProvider.azure.AZURE_CLIENT_SECRET="5c97ee08-7783-437f-899e-7b4c4e84874c" \
		--set acme.dnsProvider.azure.AZURE_TENANT_ID="566af820-2f8c-45ac-b975-647d2647b277" \
		--set acme.domains.enabled=true \
	    --set acme.domains.domainsList[0].main=*.${1:-$DNS_DOMAIN} \
		--set dashboard.enabled=true,dashboard.domain=traefik.${1:-$DNS_DOMAIN} \
		stable/traefik
	echo "done."
}

# $1 = cluster name
# $2 = datacenter name
function install_elassandra_datacenter() {
    helm install --namespace $NAMESPACE --name ${1:-"cl1"}-${2:-"dc1"} \
    --set image.pullSecrets[0]=$REGISTRY_SECRET_NAME \
    --set replicas=3 \
    --wait \
    $HELM_REPO/elassandra-datacenter
}

function create_namespace() {
    echo "Creating namespace $1"
    kubectl create namespace $1
    kubectl config set-context --current --namespace=$1
    echo "Created namespace $1"
}

function generate_ca_cert() {
	echo "generating root CA"
	openssl genrsa -out MyRootCA.key 2048
	openssl req -x509 -new -nodes -key MyRootCA.key -sha256 -days 1024 -out MyRootCA.pem
}

function generate_client_cert() {
    echo "generate client certificate"
	openssl genrsa -out MyClient1.key 2048
    openssl req -new -key MyClient1.key -out MyClient1.csr
    openssl x509 -req -in MyClient1.csr -CA MyRootCA.pem -CAkey MyRootCA.key -CAcreateserial -out MyClient1.pem -days 1024 -sha256
    openssl pkcs12 -export -out MyClient.p12 -inkey MyClient1.key -in MyClient1.pem -certfile MyRootCA.pem
}

function view_cert() {
	openssl x509 -text -in $1
}