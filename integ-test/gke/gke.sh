#!/usr/bin/env bash

set -x

GCLOUD_PROJECT=${GCLOUD_PROJECT:-strapkube1}
K8S_CLUSTER_NAME=${K8S_CLUSTER_NAME:-kube3}
GCLOUD_REGION="europe-west1"

export REGISTRY_URL=docker.io

create_regional_cluster() {
  echo "gke: creating cluster"
  gcloud container clusters create $K8S_CLUSTER_NAME \
      --region $GCLOUD_REGION \
      --project $GCLOUD_PROJECT \
      --machine-type "n1-standard-2" \
      --cluster-version=1.15 \
      --tags=$K8S_CLUSTER_NAME \
      --num-nodes "1"
  #    --enable-autoscaling

  echo "gke: getting credentials"
  gcloud container clusters get-credentials $K8S_CLUSTER_NAME --region $GCLOUD_REGION --project $GCLOUD_PROJECT

  echo "gke: bootstrap RBAC"
  kubectl create clusterrolebinding cluster-admin-binding --clusterrole cluster-admin --user $(gcloud config get-value account)
}

delete_regional_cluster() {
  gcloud container clusters delete $K8S_CLUSTER_NAME --region $GCLOUD_REGION --project $GCLOUD_PROJECT
}

create_registry() {
}



add_firwall_rule() {
  VPC_NETWORK=$(gcloud container clusters describe $K8S_CLUSTER_NAME --region $GCLOUD_REGION --format='value(network)')
  MASTER_IPV4_CIDR_BLOCK=$(gcloud container clusters describe $K8S_CLUSTER_NAME --region $GCLOUD_REGION --format='value(clusterIpv4Cidr)')
  NODE_POOLS_TARGET_TAGS=$(gcloud container clusters describe $K8S_CLUSTER_NAME --region $GCLOUD_REGION --format='value[terminator=","](nodePools.config.tags)' --flatten='nodePools[].config.tags[]' | sed 's/,\{2,\}//g')


  gcloud compute firewall-rules create "allow-apiserver-to-admission-webhook-443" \
      --allow tcp:8443 \
      --network="$VPC_NETWORK" \
      --source-ranges="$MASTER_IPV4_CIDR_BLOCK" \
      --target-tags="$NODE_POOLS_TARGET_TAGS" \
      --description="Allow apiserver access to admission webhook pod on port 443" \
      --direction INGRESS

  gcloud compute firewall-rules create "allow-elassandra-inbound" \
      --allow tcp:39000-39002 \
      --network="$VPC_NETWORK" \
      --target-tags="$NODE_POOLS_TARGET_TAGS" \
      --description="Allow elassandra inbound" \
      --direction INGRESS
}