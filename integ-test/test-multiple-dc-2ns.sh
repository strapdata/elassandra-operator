#!/usr/bin/env bash

source integ-test/test-lib.sh
setup_flavor

N=100
NS="ns4"
NS2="ns4bis"
HELM_RELEASES=("$NS-cl1-dc1" "$NS2-cl1-dc2")
NAMESPACES=("$NS" "$NS2")

test_start $0
install_elassandra_datacenter $NS cl1 dc1 1
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN --cql-status=ESTABLISHED

# create an index
kubectl exec -it elassandra-cl1-dc1-0-0 -n $NS -- bash -l -c "for i in {1..$N}; do post foo/bar '{\"foo\":\"bar\"}'; done"

# create namespace NS2 and copy cluster secrets
kubectl create namespace $NS2 || true
kubectl get secret elassandra-cl1-ca-pub --namespace=$NS --export -o yaml | kubectl apply --namespace=$NS2 -f - || true
kubectl get secret elassandra-cl1-ca-key --namespace=$NS --export -o yaml | kubectl apply --namespace=$NS2 -f - || true
kubectl get secret elassandra-cl1 --namespace=$NS --export -o yaml | kubectl apply --namespace=$NS2 -f - || true

# create dc2 in another namespace NS2
install_elassandra_datacenter $NS2 cl1 dc2 1 "cassandra.remoteSeeders[0]=https://elassandra-operator.default.svc.cluster.local/seeds/$NS/cl1/dc1"
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc2 -ns $NS2 --health GREEN

# update replication map for keyspace foo on dc2 (and system keyspaces)
cat <<EOF | kubectl apply -f -
apiVersion: elassandra.strapdata.com/v1
kind: ElassandraTask
metadata:
  name: replication-add-$$
  namespace: $NS
spec:
  cluster: "cl1"
  datacenter: "dc1"
  replication:
    action: ADD
    dcName: "dc2"
    dcSize: 1
    replicationMap:
      foo: 1
EOF
java/edctl/build/libs/edctl watch-task -n replication-add-$$ -ns $NS --phase SUCCEED

# rebuild datacenter dc2 from dc1
cat <<EOF | kubectl apply -f -
apiVersion: elassandra.strapdata.com/v1
kind: ElassandraTask
metadata:
  name: rebuild-dc2-$$
  namespace: $NS2
spec:
  cluster: "cl1"
  datacenter: "dc2"
  rebuild:
    srcDcName: "dc1"
EOF
java/edctl/build/libs/edctl watch-task -n rebuild-dc2-$$ -ns $NS2 --phase SUCCEED

# update routing table on dc2
cat <<EOF | kubectl apply -f -
apiVersion: elassandra.strapdata.com/v1
kind: ElassandraTask
metadata:
  name: updaterouting-dc2-$$
  namespace: $NS2
spec:
  cluster: "cl1"
  datacenter: "dc2"
  updateRouting: {}
EOF
java/edctl/build/libs/edctl watch-task -n updaterouting-dc2-$$ -ns $NS2 --phase SUCCEED
sleep 5

# check index
TOTAL_HIT=$(kubectl exec -it elassandra-cl1-dc2-0-0 -n $NS2 -- bash -l -c "get 'foo/bar/_search?pretty'" | tail -n +4 | jq ".hits.total")
if [ "$TOTAL_HIT" != "$N" ]; then
   finish
fi

# create a new elasticsearch index replicated on dc1 and dc2
kubectl exec -it elassandra-cl1-dc2-0-0 -n $NS2 -- bash -l -c "put _template/replicated '{ \"index_patterns\": [\"foo*\"],\"settings\": { \"index.replication\":\"dc1:1,dc2:1\" }}'"
kubectl exec -it elassandra-cl1-dc2-0-0 -n $NS2 -- bash -l -c "for i in {1..$N}; do post foo2/bar '{\"foo\":\"bar\"}'; done"
# wait for async replication en ES refresh
sleep 5
TOTAL_HIT=$(kubectl exec -it elassandra-cl1-dc1-0-0 -n $NS -- bash -l -c "get 'foo/bar/_search?pretty'" | tail -n +4 | jq ".hits.total")
if [ "$TOTAL_HIT" != "$N" ]; then
   echo "Error, expecting $N docs in foo on dc1/$NS"
   finish
fi
TOTAL_HIT=$(kubectl exec -it elassandra-cl1-dc2-0-0 -n $NS2 -- bash -l -c "get 'foo/bar/_search?pretty'" | tail -n +4 | jq ".hits.total")
if [ "$TOTAL_HIT" != "$N" ]; then
   echo "Error, expecting $N docs in foo on dc2/$NS2"
   finish
fi

# safely remove datacenter dc2
# update replication map for keyspace foo on dc2 (and system keyspaces)
cat <<EOF | kubectl apply -f -
apiVersion: elassandra.strapdata.com/v1
kind: ElassandraTask
metadata:
  name: replication-remove-dc2-$$
  namespace: $NS
spec:
  cluster: "cl1"
  datacenter: "dc1"
  replication:
    action: REMOVE
    dcName: "dc2"
EOF
java/edctl/build/libs/edctl watch-task -n replication-remove-dc2-$$ -ns $NS --phase SUCCEED

# delete dc2
uninstall_elassandra_datacenter $NS2 cl1 dc2

# wait cassandra see dead nodes
sleep 10

# remove old nodes from dc1
cat <<EOF | kubectl apply -f -
apiVersion: elassandra.strapdata.com/v1
kind: ElassandraTask
metadata:
  name: removenodes-dc2-$$
  namespace: $NS
spec:
  cluster: "cl1"
  datacenter: "dc1"
  removeNodes:
    dcName: "dc2"
EOF

# wait for leaving nodes
sleep 15

# check dead nodes are removed
kubectl exec -it elassandra-cl1-dc1-0-0 -n $NS -- bash -lc "nodetool -u cassandra -pwf /etc/cassandra/jmxremote.password  --jmxmp  --ssl status" | grep -v "DN "

# cleanup
uninstall_elassandra_datacenter $NS cl1 dc1


echo "### Test SUCCESSFUL"
test_end