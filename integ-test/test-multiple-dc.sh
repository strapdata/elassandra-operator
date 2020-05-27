#!/usr/bin/env bash

source $(dirname $0)/test-lib.sh

NS="ns2"

test_start
install_elassandra_datacenter $NS cl1 dc1 1
java/edctl/build/libs/edctl watch-dc -n $NS --health GREEN

# create an index
kubectl exec -it elassandra-cl1-dc1-0-0 -n $NS -- bash -l -c "post foo/bar '{\"foo\":\"bar\"}'"

# create dc2
install_elassandra_datacenter $NS cl1 dc2 1 "cassandra.remoteSeeders[0]=https://elassandra-operator.default.svc.cluster.local/seeds/$NS/cl1/dc1"
java/edctl/build/libs/edctl watch-dc -n $NS --health GREEN

# update replication of dc2 on dc1
cat <<EOF | kubectl apply -f -
apiVersion: elassandra.strapdata.com/v1
kind: ElassandraTask
metadata:
  name: replication-1
  namespace: $NS
spec:
  cluster: "cl1"
  datacenter: "dc1"
  replication:
    action: ADD
    dcName: "dc2"
    dcSize: 1
    replicationMap:
      dc2: 1
EOF

# rebuild datacenter dc2 from dc1
# update routing table on dc2

uninstall_elassandra_datacenter $NS cl1 dc1
uninstall_elassandra_datacenter $NS cl1 dc2

echo "Test SUCCESSFUL"
test_end