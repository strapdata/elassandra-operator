#!/usr/bin/env bash

source integ-test/test-lib.sh
setup_flavor

NS="replacenode"
HELM_RELEASE="$NS-cl1-dc1"

test_start $0
install_elassandra_datacenter $NS cl1 dc1 3
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN -r 3
test "$(kubectl get edc elassandra-cl1-dc1 -n $NS -o jsonpath='{.status.reaperPhase}')" == "NONE"

# remove PVC protection for test
PVC=data-volume-elassandra-cl1-dc1-0-0
PV=$(kubectl get pvc $PVC -o jsonpath='{.spec.volumeName}')
kubectl patch pv $PV -p '{"metadata":{"finalizers":null}}'
kubectl patch pvc $PVC -p '{"metadata":{"finalizers":null}}'
# delete PV
kubectl delete pv $PV
kubectl delete pvc $PVC

# delete pod to restart with an empty disk
kubectl delete pod elassandra-cl1-dc1-0-0
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN -r 3


uninstall_elassandra_datacenter $NS cl1 dc1
echo "### Test SUCCESSFUL"
test_end