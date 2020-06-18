#!/usr/bin/env bash

source integ-test/test-lib.sh
setup_flavor

NS="hostnet"
HELM_RELEASES=("$NS-cl1-dc1")
NAMESPACES=("$NS")

test_start $0
install_elassandra_datacenter $NS cl1 dc1 1 'networking.hostNetworkEnabled=true'
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN -r 1

ELASSANDRA_OPERATOR_POD=$(kubectl get pod -l app=elassandra-operator -n default -o custom-columns=NAME:.metadata.name --no-headers)
SEED_IP=$(kubectl exec -n default $ELASSANDRA_OPERATOR_POD -- curl -k "https://localhost/seeds/$NS/cl1/dc1" 2>/dev/null | jq -r ".[0]")
if [ -z "$SEED_IP" ]; then
  echo "Seed IP is empty"
  error
fi

HOST_IP=$(kubectl get pod elassandra-cl1-dc1-0-0 -n $NS -o custom-columns=HOST_IP:.status.hostIP --no-headers)
if [ -z "$HOST_IP" ]; then
  echo "status.hostIP is empty"
  error
fi

if [ "$HOST_IP" != "$SEED_IP" ]; then
  echo "status.hostIP not equals to sedd IP"
  error
fi

# cleanup
echo "Test SUCCESSFUL"
finish
