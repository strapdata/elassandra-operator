#!/usr/bin/env bash
#
# Require az cli 2.0.76+
# On  Mac: brew upgrade azure-cli
# az extension update --name aks-preview
# az feature register --name NodePublicIPPreview --namespace Microsoft.ContainerService
# az account set --subscription 72738c1b-8ae6-4f23-8531-5796fe866f2e
set -x

HELM_REPO=../helm/src/main/helm
REGISTRY_CONFIG_FILE="dockerconfig.json"
REGISTRY_SECRET_NAME="strapregistry"
ELASSANDRA_OPERATOR_TAG="6.8.4.3"

test_start() {
  set -x
  set -o pipefail
  trap finish ERR
}

test_end() {
  set +e
  trap - ERR
}

finish() {
  echo "ERROR occurs, test FAILED"
  exit 1
}

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

function create_registry_pull_secret() {
    kubectl create secret generic strapregistry --from-file=.dockerconfigjson=$REGISTRY_CONFIG_FILE --type=kubernetes.io/dockerconfigjson
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

# $1 = RESOURCE_GROUP_NAME
# $2 = K8S_CLUSTER_NAME
function destroy_aks_cluster() {
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
    $HELM_REPO/elassandra-operator
}

function uninstall_elassandra_operator() {
    helm delete --purge strapkop
}

# Deploy single node cluster in namespace=cluster_name
# $1 = cluster name
# $2 = cluster
# $3 = datacenter name
# $4 = number of nodes
function install_elassandra_datacenter() {
    local ns=${1:-"default"}
    local cl=${2:-"cl1"}
    local dc=${3:-"dc1"}
    local sz=${4:-"1"}
    helm install --namespace "$ns" --name "$cl-$dc" \
    --set image.pullSecrets[0]=$REGISTRY_SECRET_NAME \
    --set reaper.enabled="false" \
    --set kibana.enabled="true" \
    --set sslStoragePort="38001",jmxPort="35001",prometheusPort="34001" \
    --set externalDns.enabled="true",externalDns.root="xxxx.yyyy",externalDns.domain="test.strapkube.com" \
    --set replicas="$sz" \
    --wait \
    $HELM_REPO/elassandra-datacenter
    echo "Datacenter $cl-$dc size=$sz deployed in namespace $ns"
}

function uninstall_elassandra_datacenter() {
    local cl=${2:-"cl1"}
    local dc=${3:-"dc1"}
    helm delete --purge "$cl-$dc"
    echo "Datacenter $cl-$dc uninstalled"
}

function scale_elassandra_datacenter() {
    local cl=${1:-"cl1"}
    local dc=${2:-"dc1"}
    local sz=${3:-"1"}
    helm upgrade --reuse-values --set replicas="$sz" "$cl-$dc" $HELM_REPO/elassandra-datacenter
    echo "Datacenter $cl-$dc scale size=$sz"
}

function elassandra_datacenter_wait_running() {
    kb get elassandradatacenters elassandra-cl1-dc1 -o yaml -o jsonpath="{.status.phase}"
}

function park_elassandra_datacenter() {
    local cl=${1:-"cl1"}
    local dc=${2:-"dc1"}
    helm upgrade --reuse-values --set parked="true" "$cl-$dc" $HELM_REPO/elassandra-datacenter
    echo "Datacenter $cl-$dc parked"
}

function unpark_elassandra_datacenter() {
    local cl=${1:-"cl1"}
    local dc=${2:-"dc1"}
    helm upgrade --reuse-values --set parked="false" "$cl-$dc" $HELM_REPO/elassandra-datacenter
    echo "Datacenter $cl-$dc unparked"
}

function reaper_enable() {
    local cl=${1:-"cl1"}
    local dc=${2:-"dc1"}
    helm upgrade --reuse-values --set reaper.enabled="true",reaper.loggingLevel="TRACE" "$cl-$dc" $HELM_REPO/elassandra-datacenter
    echo "Datacenter $cl-$dc reaper enabled"
}

function reaper_disable() {
    local cl=${1:-"cl1"}
    local dc=${2:-"dc1"}
    helm upgrade --reuse-values --set reaper.enabled="false" "$cl-$dc" $HELM_REPO/elassandra-datacenter
    echo "Datacenter $cl-$dc reaper disabled"
}

function downgrade_elassandra_datacenter() {
    local cl=${1:-"cl1"}
    local dc=${2:-"dc1"}
    helm upgrade --reuse-values --set elassandraImage="strapdata.azurecr.io/strapdata/elassandra-node-dev:6.2.3.26" "$cl-$dc" $HELM_REPO/elassandra-datacenter
    echo "Datacenter $cl-$dc downgrade to 6.2.3.26"
}

function add_memory_elassandra_datacenter() {
    local cl=${1:-"cl1"}
    local dc=${2:-"dc1"}
    helm upgrade --reuse-values --set resources.limits.memory="3Gi" --set "$cl-$dc" $HELM_REPO/elassandra-datacenter
    echo "Datacenter $cl-$dc update memory to 3Gi"
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