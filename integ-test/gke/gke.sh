#!/usr/bin/env bash

set -x

PROJECT=${PROJECT:-strapkube1}
CLUSTER=${CLUSTER:-test}
ZONE=${ZONE:-europe-west1-b}
NODES=${NODES:-1}

export REGISTRY_URL=docker.io

create_cluster() {
  echo "gke: creating cluster"
  gcloud container clusters create $CLUSTER \
      --zone "$ZONE" \
      --project $PROJECT \
      --machine-type "n1-standard-2" \
      --num-nodes "1"
  #    --max-nodes "6" \
  #    --enable-autoscaling


  echo "gke: getting credentials"
  gcloud container clusters get-credentials $CLUSTER --zone $ZONE --project $PROJECT

  echo "gke: bootstrap RBAC"
  kubectl create clusterrolebinding cluster-admin-binding --clusterrole cluster-admin --user $(gcloud config get-value account)
}

create_registry() {
  echo "gke: authenticate to gcr"
  gcloud auth configure-docker $REGISTRY_NAME
}