#!/usr/bin/env bash

source integ-test/test-lib.sh
setup_flavor

NS="kibana1"
NAMESPACES=("$NS")
HELM_RELEASES=("$NS-cl1-dc1")

test_start $0

# run cassandra only
install_elassandra_datacenter $NS cl1 dc1 1 'kibana.enabled=true,reaper.enabled=false'
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN -r 1 --cql-status=ESTABLISHED

sleep 15
KIBANA_POD=$(kubectl get pod -l app=kibana -n $NS --no-headers | awk '{ print $1 }')
kubectl wait pod $KIBANA_POD -n $NS --for=condition=Ready --timeout=300s

scale_elassandra_datacenter $NS cl1 dc1 3
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN -r 3

uninstall_elassandra_datacenter $NS cl1 dc1
echo "### Test SUCCESSFUL"
test_end