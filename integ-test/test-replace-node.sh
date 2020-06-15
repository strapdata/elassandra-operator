#!/usr/bin/env bash

source integ-test/test-lib.sh
setup_flavor

NS="replace_node"
#HELM_RELEASE="$NS-cl1-dc1"

test_start $0
install_elassandra_datacenter $NS cl1 dc1 3
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN -r 3
test "$(kubectl get edc elassandra-cl1-dc1 -n $NS -o jsonpath='{.status.reaperPhase}')" == "NONE"

# get first node pod ip
POD_IP=$(kubectl get pod elassandra-cl1-dc1-0-0 -ojson
# delete PV
PV=$(kubectl get pvc )
kubectl delete pv $PV
# add annotation
kubectl annotate pod elassandra-cl1-dc1-0-0 "elassandra.strapdata.com/jvm.options=-Dreplace_address_first_boot=$POD_IP"
# delete pod to restart with an empty disk
kubectl delete pod elassandra-cl1-dc1-0-0

uninstall_elassandra_datacenter $NS cl1 dc1
echo "### Test SUCCESSFUL"
test_end