#!/usr/bin/env bash

source integ-test/test-lib.sh
setup_flavor

NS="ns1"
HELM_RELEASES=("$NS-cl1-dc1")
NAMESPACES=("$NS")

test_start $0
install_elassandra_datacenter $NS cl1 dc1 1
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN
test "$(kubectl get edc elassandra-cl1-dc1 -n $NS -o jsonpath='{.status.needCleanup}')" == "false"

scale_elassandra_datacenter $NS cl1 dc1 3
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN -r 3
test "$(kubectl get edc elassandra-cl1-dc1 -n $NS -o jsonpath='{.status.needCleanup}')" == "true"

# cleanup all keyspaces
cat <<EOF | kubectl apply -f -
apiVersion: elassandra.strapdata.com/v1
kind: ElassandraTask
metadata:
  name: cleanup-$$
  namespace: $NS
spec:
  cluster: "cl1"
  datacenter: "dc1"
  cleanup: {}
EOF
java/edctl/build/libs/edctl watch-task -n cleanup-$$ -ns $NS --phase SUCCEED
test "$(kubectl get edc elassandra-cl1-dc1 -n $NS -o jsonpath='{.status.needCleanup}')" == "false"

park_elassandra_datacenter $NS cl1 dc1
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS -p PARKED -r 0
sleep 10
unpark_elassandra_datacenter $NS cl1 dc1
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS -p RUNNING --health GREEN -r 3

# scale down
scale_elassandra_datacenter $NS cl1 dc1 2
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN -r 2
kubectl delete pvc data-volume-elassandra-cl1-dc1-0-0 -n $NS

# scale up
scale_elassandra_datacenter $NS cl1 dc1 3
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN -r 3

uninstall_elassandra_datacenter $NS cl1 dc1
echo "### Test SUCCESSFUL"
test_end