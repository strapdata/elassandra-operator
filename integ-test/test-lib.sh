#!/usr/bin/env bash
#
# Require az cli 2.0.76+
# On  Mac: brew upgrade azure-cli
# az extension update --name aks-preview
# az feature register --name NodePublicIPPreview --namespace Microsoft.ContainerService
# az account set --subscription 72738c1b-8ae6-4f23-8531-5796fe866f2e
set -x

BASE_DIR=$(dirname $0)
HELM_REPO=helm/src/main/helm

export ELASSANDRA_OPERATOR_TAG=$(awk -F "=" '/version/ { print $2 }' gradle.properties)
export ELASSANDRA_NODE_TAG=$(head -n 1 docker/supportedElassandraVersions.txt)

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

init_cluster() {
  case "$K8S_FLAVOR" in
  "aks")
    echo "Loading AKS library"
    source $BASE_DIR/aks/test-lib-aks.sh
    ;;
  "gke")
    echo "Loading GKE library"
    source $BASE_DIR/gke/test-lib-gke.sh
    ;;
  *)
    echo "Loading Kind library"
    source $BASE_DIR/kind/test-lib-kind.sh
    ;;
  esac

  create_cluster
  init_helm
  install_elassandra_operator
}

init_helm() {
   echo "Installing HELM"
	 helm init
	 kubectl -n kube-system get po || helm init
	 kubectl create serviceaccount --namespace kube-system tiller
	 kubectl create clusterrolebinding tiller-cluster-rule --clusterrole=cluster-admin --serviceaccount=kube-system:tiller
	 kubectl patch deploy --namespace kube-system tiller-deploy -p '{"spec":{"template":{"spec":{"serviceAccount":"tiller"}}}}'
	 helm init --wait

  # K8s 1.16+ apiVersion issue
	# helm init --service-account tiller --override spec.selector.matchLabels.'name'='tiller',spec.selector.matchLabels.'app'='helm' --output yaml | sed 's@apiVersion: extensions/v1beta1@apiVersion: apps/v1@' | kubectl apply -f -
  echo "HELM installed"
}

# deploy the elassandra operator in $1 = namespace
install_elassandra_operator() {
    echo "Installing elassandra-operator in namespace ${1:-default}"
    helm install --namespace ${1:-default} --name strapkop \
    --set image.repository=$REGISTRY_URL/strapdata/elassandra-operator-dev \
    --set image.tag="$ELASSANDRA_OPERATOR_TAG" \
    --set image.pullSecrets[0]="$REGISTRY_SECRET_NAME" \
    --wait \
    $HELM_REPO/elassandra-operator
    echo "Elassandra-operator installed"
}

uninstall_elassandra_operator() {
    helm delete --purge strapkop
}

# Deploy single node cluster in namespace=cluster_name
# $1 = cluster name
# $2 = cluster
# $3 = datacenter name
# $4 = number of nodes
install_elassandra_datacenter() {
    local ns=${1:-"default"}
    local cl=${2:-"cl1"}
    local dc=${3:-"dc1"}
    local sz=${4:-"1"}

    local registry=""
    if [ "$REGISTRY_SECRET_NAME" != "" ]; then
       registry=",image.pullSecrets[0]=$REGISTRY_SECRET_NAME"
    fi

    helm install --namespace "$ns" --name "$ns-$cl-$dc" \
    --set image.elassandraRepository=$REGISTRY_URL/strapdata/elassandra-node-dev \
    --set image.tag=$ELASSANDRA_NODE_TAG \
    --set dataVolumeClaim.storageClassName=${STORAGE_CLASS_NAME:-"standard"}$registry \
    --set kibana.enabled="false" \
    --set reaper.enabled="false" \
    --set reaper.image="$REGISTRY_URL/strapdata/cassandra-reaper:2.1.0-SNAPSHOT-strapdata-1" \
    --set sslStoragePort="38001",jmxPort="35001",prometheusPort="34001" \
    --set externalDns.enabled="false",externalDns.root="xxxx.yyyy",externalDns.domain="test.strapkube.com" \
    --set replicas="$sz" \
    --wait \
    $HELM_REPO/elassandra-datacenter
    echo "Datacenter $cl-$dc size=$sz deployed in namespace $ns"
}

uninstall_elassandra_datacenter() {
    local ns=${1:-"default"}
    local cl=${2:-"cl1"}
    local dc=${3:-"dc1"}
    helm delete --purge "$ns-$cl-$dc"
    echo "Datacenter $cl-$dc uninstalled in namespace $ns"
}

scale_elassandra_datacenter() {
    local ns=${1:-"default"}
    local cl=${2:-"cl1"}
    local dc=${3:-"dc1"}
    local sz=${4:-"1"}
    helm upgrade --reuse-values --set replicas="$sz" "$ns-$cl-$dc" $HELM_REPO/elassandra-datacenter
    echo "Datacenter $ns-$cl-$dc scale size=$sz"
}

elassandra_datacenter_wait_running() {
    kb get elassandradatacenters elassandra-cl1-dc1 -o yaml -o jsonpath="{.status.phase}"
}

park_elassandra_datacenter() {
    local ns=${1:-"default"}
    local cl=${2:-"cl1"}
    local dc=${3:-"dc1"}
    helm upgrade --reuse-values --set parked="true" "$ns-$cl-$dc" $HELM_REPO/elassandra-datacenter
    echo "Datacenter $ns-$cl-$dc parked"
}

unpark_elassandra_datacenter() {
    local ns=${1:-"default"}
    local cl=${2:-"cl1"}
    local dc=${3:-"dc1"}
    helm upgrade --reuse-values --set parked="false" "$ns-$cl-$dc" $HELM_REPO/elassandra-datacenter
    echo "Datacenter $ns-$cl-$dc unparked"
}

reaper_enable() {
    local ns=${1:-"default"}
    local cl=${2:-"cl1"}
    local dc=${3:-"dc1"}
    helm upgrade --reuse-values --set reaper.enabled="true",reaper.loggingLevel="TRACE" "$ns-$cl-$dc" $HELM_REPO/elassandra-datacenter
    echo "Datacenter $cl-$dc reaper enabled"
}

reaper_disable() {
    local ns=${1:-"default"}
    local cl=${2:-"cl1"}
    local dc=${3:-"dc1"}
    helm upgrade --reuse-values --set reaper.enabled="false" "$ns-$cl-$dc" $HELM_REPO/elassandra-datacenter
    echo "Datacenter $cl-$dc reaper disabled"
}

downgrade_elassandra_datacenter() {
    local ns=${1:-"default"}
    local cl=${2:-"cl1"}
    local dc=${3:-"dc1"}
    helm upgrade --reuse-values --set elassandraImage="strapdata.azurecr.io/strapdata/elassandra-node-dev:6.2.3.26" "$ns-$cl-$dc" $HELM_REPO/elassandra-datacenter
    echo "Datacenter $cl-$dc downgrade to 6.2.3.26"
}

add_memory_elassandra_datacenter() {
    local ns=${1:-"default"}
    local cl=${2:-"cl1"}
    local dc=${3:-"dc1"}
    helm upgrade --reuse-values --set resources.limits.memory="3Gi" "$ns-$cl-$dc" $HELM_REPO/elassandra-datacenter
    echo "Datacenter $cl-$dc update memory to 3Gi"
}


create_namespace() {
    echo "Creating namespace $1"
    kubectl create namespace $1
    kubectl config set-context --current --namespace=$1
    echo "Created namespace $1"
}

generate_ca_cert() {
	echo "generating root CA"
	openssl genrsa -out MyRootCA.key 2048
	openssl req -x509 -new -nodes -key MyRootCA.key -sha256 -days 1024 -out MyRootCA.pem
}

generate_client_cert() {
    echo "generate client certificate"
    openssl genrsa -out MyClient1.key 2048
    openssl req -new -key MyClient1.key -out MyClient1.csr
    openssl x509 -req -in MyClient1.csr -CA MyRootCA.pem -CAkey MyRootCA.key -CAcreateserial -out MyClient1.pem -days 1024 -sha256
    openssl pkcs12 -export -out MyClient.p12 -inkey MyClient1.key -in MyClient1.pem -certfile MyRootCA.pem
}

view_cert() {
    openssl x509 -text -in $1
}