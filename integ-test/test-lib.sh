#!/usr/bin/env bash
#
# Require az cli 2.0.76+
# On  Mac: brew upgrade azure-cli
# az extension update --name aks-preview
# az feature register --name NodePublicIPPreview --namespace Microsoft.ContainerService
# az account set --subscription 72738c1b-8ae6-4f23-8531-5796fe866f2e
set -x

BASE_DIR=integ-test
HELM_REPO=helm/src/main/helm

export ELASSANDRA_OPERATOR_TAG=$(awk -F "=" '/version/ { print $2 }' gradle.properties)
export ELASSANDRA_NODE_TAG=$(head -n 1 docker/supportedElassandraVersions.txt)

test_start() {
  echo "### Starting $1"
  set -x
  set -o pipefail
  trap error ERR
}

test_end() {
  set +e
  trap - ERR
  finish
}

error() {
  echo "ERROR occurs, test FAILED"
  finish
  kubectl logs --tail=500 -l app=elassandra-operator -n default
  exit 1
}

finish() {
  for i in "${HELM_RELEASES[@]}"; do
    helm delete --purge $i
  done
  for i in "${NAMESPACES[@]}"; do
    kubectl delete namespace $i
  done
}

setup_flavor() {
  case "$K8S_FLAVOR" in
  "aks")
    echo "Loading AKS library"
    source $BASE_DIR/aks/aks.sh
    ;;
  "gke")
    echo "Loading GKE library"
    source $BASE_DIR/gke/gke.sh
    ;;
  *)
    echo "Loading Kind library"
    source $BASE_DIR/kind/kind.sh
    ;;
  esac
}

setup_cluster() {
  setup_flavor
  create_cluster ${1:-cluster1} ${2:-6}
  create_registry
  init_helm
}

init_helm() {
   echo "Installing HELM"
   kubectl create serviceaccount --namespace kube-system tiller
   kubectl create clusterrolebinding tiller-cluster-rule --clusterrole=cluster-admin --serviceaccount=kube-system:tiller
   helm init --wait --service-account tiller

  # K8s 1.16+ apiVersion issue
  # helm init --service-account tiller --override spec.selector.matchLabels.'name'='tiller',spec.selector.matchLabels.'app'='helm' --output yaml | sed 's@apiVersion: extensions/v1beta1@apiVersion: apps/v1@' | kubectl apply -f -
  echo "HELM installed"
}

# deploy the elassandra operator
# $1 = namespace
# $2 = helm settings
install_elassandra_operator() {
    echo "Installing elassandra-operator in namespace ${1:-default}"

    local registry=""
    if [ "$REGISTRY_SECRET_NAME" != "" ]; then
       registry=",image.pullSecrets[0]=$REGISTRY_SECRET_NAME"
    fi

    local args=""
    if [ "$2" != "" ]; then
       args=",$2"
    fi

    helm install --namespace ${1:-default} --name strapkop \
    --set image.repository=$REGISTRY_URL/strapdata/elassandra-operator${registry} \
    --set image.tag="$ELASSANDRA_OPERATOR_TAG" \
    --set image.pullSecrets[0]="$REGISTRY_SECRET_NAME"$args \
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
# $5 = helm settings
install_elassandra_datacenter() {
    local ns=${1:-"default"}
    local cl=${2:-"cl1"}
    local dc=${3:-"dc1"}
    local sz=${4:-"1"}

    local registry=""
    if [ "$REGISTRY_SECRET_NAME" != "" ]; then
       registry=",image.pullSecrets[0]=$REGISTRY_SECRET_NAME"
    fi

    local args=""
    if [ "$5" != "" ]; then
       args=",$5"
    fi

    helm install --namespace "$ns" --name "$ns-$cl-$dc" \
    --set image.elassandraRepository=$REGISTRY_URL/strapdata/elassandra-node${registry} \
    --set image.tag=$ELASSANDRA_NODE_TAG \
    --set dataVolumeClaim.storageClassName=${STORAGE_CLASS_NAME:-"standard"} \
    --set kibana.enabled="false" \
    --set reaper.enabled="false",reaper.image="$REGISTRY_URL/strapdata/cassandra-reaper:2.1.0-SNAPSHOT-strapkop" \
    --set cassandra.sslStoragePort="38001",jvm.jmxPort="35001",prometheus.port="34001" \
    --set externalDns.enabled="false",externalDns.root="xxxx.yyyy",externalDns.domain="test.strapkube.com" \
    --set replicas="$sz"$args \
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

kibana_enable() {
    local ns=${1:-"default"}
    local cl=${2:-"cl1"}
    local dc=${3:-"dc1"}
    helm upgrade --reuse-values --set kibana.enabled="true" "$ns-$cl-$dc" $HELM_REPO/elassandra-datacenter
    echo "Datacenter $cl-$dc kibana enabled"
}

kibana_disable() {
    local ns=${1:-"default"}
    local cl=${2:-"cl1"}
    local dc=${3:-"dc1"}
    helm upgrade --reuse-values --set kibana.enabled="false" "$ns-$cl-$dc" $HELM_REPO/elassandra-datacenter
    echo "Datacenter $cl-$dc kibana disabled"
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

unction deploy_prometheus_operator() {
  helm install $HELM_DEBUG --name promop \
    --set prometheus.ingress.enabled=true,prometheus.ingress.hosts[0]="prometheus.${DNS_DOMAIN}",prometheus.ingress.annotations."kubernetes\.io/ingress\.class"="traefik" \
    --set alertmanager.ingress.enabled=true,alertmanager.ingress.hosts[0]="alertmanager.${DNS_DOMAIN}",alertmanager.ingress.annotations."kubernetes\.io/ingress\.class"="traefik" \
    --set grafana.ingress.enabled=true,grafana.ingress.hosts[0]="grafana.${DNS_DOMAIN}",grafana.ingress.annotations."kubernetes\.io/ingress\.class"="traefik" \
    -f prometheus-operator-values.yaml \
  /Users/vroyer/dev/git/helm/charts/stable/prometheus-operator
}

function upgrade_prometheus_operator() {
  helm upgrade $HELM_DEBUG \
    --set prometheus.ingress.enabled=true,prometheus.ingress.hosts[0]="prometheus.${DNS_DOMAIN}",prometheus.ingress.annotations."kubernetes\.io/ingress\.class"="traefik" \
    --set alertmanager.ingress.enabled=true,alertmanager.ingress.hosts[0]="alertmanager.${DNS_DOMAIN}",alertmanager.ingress.annotations."kubernetes\.io/ingress\.class"="traefik" \
    --set grafana.ingress.enabled=true,grafana.ingress.hosts[0]="grafana.${DNS_DOMAIN}",grafana.ingress.annotations."kubernetes\.io/ingress\.class"="traefik" \
    -f prometheus-operator-values.yaml \
  promop /Users/vroyer/dev/git/helm/charts/stable/prometheus-operator
}

function undeploy_prometheus_operator() {
  kubectl delete crd prometheuses.monitoring.coreos.com
  kubectl delete crd prometheusrules.monitoring.coreos.com
  kubectl delete crd servicemonitors.monitoring.coreos.com
  kubectl delete crd alertmanagers.monitoring.coreos.com
  helm delete --purge promop
}
