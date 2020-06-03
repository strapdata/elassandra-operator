#!/usr/bin/env bash

source integ-test/test-lib.sh
setup_flavor

NS="ns3"

install_elassandra_datacenter $NS cl1 dc1 1

# cannot change the cluster name
kubectl patch edc elassandra-cl1-dc1 -n $NS --type="merge" --patch '{"spec": {"clusterName": "cl2" }}'
if [ $? -eq 0 ]; then
  echo "Webhook admission should refuse cluster name change"
  finish
fi

# cannot change the datacenter name
kubectl patch edc elassandra-cl1-dc1 -n $NS --type="merge" --patch '{"spec": {"datacenterName": "dc2" }}'
if [ $? -eq 0 ]; then
  echo "Webhook admission should refuse datacenter name change"
  finish
fi

# cleanup
uninstall_elassandra_datacenter $NS cl1 dc1
echo "Test SUCCESSFUL"
