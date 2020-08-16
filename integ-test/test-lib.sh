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

#ELASSANDRA_OPERATOR_TAG=$(awk -F "=" '/version/ { print $2 }' gradle.properties)
ELASSANDRA_OPERATOR_TAG=latest
ELASSANDRA_NODE_TAG=$(head -n 1 docker/supportedElassandraVersions.txt)


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

destroy_cluster() {
  setup_flavor
  delete_cluster
}

init_helm() {
  echo "Installing HELM"
  kubectl create serviceaccount --namespace kube-system tiller
  kubectl create clusterrolebinding tiller-cluster-rule --clusterrole=cluster-admin --serviceaccount=kube-system:tiller
  #helm init --wait --service-account tiller
  # HELM 2 K8s 1.16+ apiVersion issue
  helm init  --wait --service-account tiller --override spec.selector.matchLabels.'name'='tiller',spec.selector.matchLabels.'app'='helm' --output yaml | sed 's@apiVersion: extensions/v1beta1@apiVersion: apps/v1@' | kubectl apply -f -

  helm repo add bitnami https://charts.bitnami.com/bitnami
  echo "HELM installed"
}

# $1 secret name
# $2 target context
# $3 target namespace
copy_secret_to_context() {
  kubectl get secret $1 --export -o yaml | kubectl apply --context $2 -n $3 -f -
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

    helm install --namespace ${1:-default} --name elassop \
    --set image.repository=$REGISTRY_URL/strapdata/elassandra-operator${registry} \
    --set image.tag="$ELASSANDRA_OPERATOR_TAG"$args \
    --wait \
    $HELM_REPO/elassandra-operator
    echo "Elassandra-operator installed"
}

uninstall_elassandra_operator() {
    helm delete --purge elassop
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
    --set image.repository=$REGISTRY_URL/strapdata/elassandra-node${registry} \
    --set image.tag=$ELASSANDRA_NODE_TAG \
    --set dataVolumeClaim.storageClassName=${STORAGE_CLASS_NAME:-"standard"} \
    --set kibana.enabled="false" \
    --set reaper.enabled="false",reaper.image="$REGISTRY_URL/strapdata/cassandra-reaper:2.1.0-SNAPSHOT-strapkop" \
    --set cassandra.sslStoragePort="39000",cassandra.nativePort="39001" \
    --set elasticsearch.httpPort="39002",elasticsearch.transportPort="39003" \
    --set jvm.jmxPort="39004",jvm.jdb="39005",prometheus.port="39006" \
    --set replicas="$sz"$args \
    --wait $HELM_REPO/elassandra-datacenter
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

#-----------------------------------------------------------
#
export DNS_DOMAIN="test.strapkube.com"
export TRAEFIK_NAME="traefik-dc1"
export TRAFIK_FQDN="${TRAEFIK_NAME}.${DNS_DOMAIN}"


create_sp_for_dns_update() {
  az ad sp create-for-rbac --name http://elassop-dns-updater
  az role assignment create --assignee http://elassop-dns-updater --role befefa01-2a29-4197-83a8-272ff33ce314  --resource-group $AZURE_DNS_RESOURCE_GROUP
}

delete_sp_for_dns_update() {
  az role assignment delete --assignee http://elassop-dns-updater --role befefa01-2a29-4197-83a8-272ff33ce314  --resource-group ${AZURE_DNS_RESOURCE_GROUP}
  az ad sp delete --id http://elassop-dns-updater
}


deploy_external_dns_azure() {
  helm install $HELM_DEBUG --name my-externaldns --namespace default \
    --set logLevel="debug" \
    --set rbac.create=true \
    --set policy="sync",txtPrefix=$(kubectl config current-context)\
    --set sources[0]="service",sources[1]="ingress",sources[2]="crd" \
    --set crd.create=true,crd.apiversion="externaldns.k8s.io/v1alpha1",crd.kind="DNSEndpoint" \
    --set provider="azure" \
    --set azure.secretName="$AZURE_DNS_SECRET_NAME",azure.resourceGroup="$AZURE_DNS_RESOURCE_GROUP" \
    --set azure.tenantId="$AZURE_DNS_TENANT_ID",azure.subscriptionId="$AZURE_SUBSCRIPTION_ID" \
    --set azure.aadClientId="$AZURE_DNS_CLIENT_ID",azure.aadClientSecret="$AZURE_DNS_CLIENT_SECRET" \
    stable/external-dns
}

deploy_prometheus_operator() {
  helm install $HELM_DEBUG --name my-promop \
    --set prometheus.ingress.enabled=true,prometheus.ingress.hosts[0]="prometheus.${TRAFIK_FQDN}",prometheus.ingress.annotations."kubernetes\.io/ingress\.class"="traefik" \
    --set alertmanager.ingress.enabled=true,alertmanager.ingress.hosts[0]="alertmanager.${TRAFIK_FQDN}",alertmanager.ingress.annotations."kubernetes\.io/ingress\.class"="traefik" \
    --set grafana.ingress.enabled=true,grafana.ingress.hosts[0]="grafana.${TRAFIK_FQDN}",grafana.ingress.annotations."kubernetes\.io/ingress\.class"="traefik" \
    -f integ-test/prometheus-operator-values.yaml \
    stable/prometheus-operator
}

upgrade_prometheus_operator() {
  helm upgrade $HELM_DEBUG \
    --set prometheus.ingress.enabled=true,prometheus.ingress.hosts[0]="prometheus.${TRAFIK_FQDN}",prometheus.ingress.annotations."kubernetes\.io/ingress\.class"="traefik" \
    --set alertmanager.ingress.enabled=true,alertmanager.ingress.hosts[0]="alertmanager.${TRAFIK_FQDN}",alertmanager.ingress.annotations."kubernetes\.io/ingress\.class"="traefik" \
    --set grafana.ingress.enabled=true,grafana.ingress.hosts[0]="grafana.${TRAFIK_FQDN}",grafana.ingress.annotations."kubernetes\.io/ingress\.class"="traefik" \
    -f integ-test/prometheus-operator-values.yaml \
    my-promop stable/prometheus-operator
}

undeploy_prometheus_operator() {
  kubectl delete crd prometheuses.monitoring.coreos.com
  kubectl delete crd prometheusrules.monitoring.coreos.com
  kubectl delete crd servicemonitors.monitoring.coreos.com
  kubectl delete crd podmonitors.monitoring.coreos.com
  kubectl delete crd alertmanagers.monitoring.coreos.com
  kubectl delete crd thanosrulers.monitoring.coreos.com
  helm delete --purge my-promop
}

deploy_traefik() {
  echo "Deploying traefik proxy with DNS fqdn=${1:-$TRAFIK_FQDN}"
  helm install $HELM_DEBUG --name traefik --namespace kube-system \
    --set rbac.enabled=true,debug.enabled=true \
    --set dashboard.enabled=true,dashboard.domain=dashboard.${1:-$TRAFIK_FQDN} \
    --set service.annotations."external-dns\.alpha\.kubernetes\.io/hostname"="*.${1:-$TRAFIK_FQDN}" \
    stable/traefik
  echo "done."
}

deploy_coredns_forwarder() {
  HOST_ALIASES=$(kubectl get nodes -o custom-columns='INTERNAL-IP:.status.addresses[?(@.type=="InternalIP")].address,PUBLIC-IP:.metadata.labels.kubernetes\.strapdata\.com/public-ip' --no-headers |\
  awk '{ gsub(/\./,"-",$2); printf("--set nodes.hosts[%d].name=%s,nodes.hosts[%d].value=%s ",NR-1, $2, NR-1, $1); }')
  kubectl delete configmap --namespace kube-system coredns-custom
  helm install $HELM_DEBUG --name coredns-forwarder --namespace kube-system \
  --set forwarders.domain="${DNS_DOMAIN}" \
  --set forwarders.hosts[0]="40.90.4.8" \
  --set forwarders.hosts[1]="64.4.48.8" \
  --set forwarders.hosts[2]="13.107.24.8" \
  --set forwarders.hosts[3]="13.107.160.8" \
  --set nodes.domain=internal.strapdata.com \
  $HOST_ALIASES \
  strapdata/coredns-forwarder
  kubectl delete pod --namespace kube-system -l k8s-app=kube-dns
}