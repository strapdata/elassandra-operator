#!/usr/bin/env bash

source integ-test/test-lib.sh
setup_flavor

NS="ns3"

install_elassandra_datacenter $NS cl1 dc1 1
# wait for the DC to be managed by the operator
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN -r 1

# cannot change the cluster name
kubectl patch elassandradatacenter elassandra-cl1-dc1 -n $NS --type="merge" --patch '{"spec": {"clusterName": "cl2" }}'
if [ $? -eq 0 ]; then
  echo "Webhook admission should refuse cluster name change"
  finish
fi

# cannot change the datacenter name
kubectl patch elassandradatacenter elassandra-cl1-dc1 -n $NS --type="merge" --patch '{"spec": {"datacenterName": "dc2" }}'
if [ $? -eq 0 ]; then
  echo "Webhook admission should refuse datacenter name change"
  finish
fi

kubectl patch elassandradatacenter elassandra-cl1-dc1 -n $NS --type="merge" --patch '{"spec": {"dataVolumeClaim": { "storageClassName":"dummy" }}}'
if [ $? -eq 0 ]; then
  echo "Webhook admission should refuse storageClassName change"
  finish
fi

# cleanup
uninstall_elassandra_datacenter $NS cl1 dc1
echo "Test SUCCESSFUL"
