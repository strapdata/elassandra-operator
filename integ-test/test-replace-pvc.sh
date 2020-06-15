#!/usr/bin/env bash

source integ-test/test-lib.sh
setup_flavor

N=100
NS="replacenode"
HELM_RELEASE="$NS-cl1-dc1"

test_start $0
install_elassandra_datacenter $NS cl1 dc1 3
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN -r 3 --cql-status=ESTABLISHED

# create an index
kubectl exec -it elassandra-cl1-dc1-0-0 -n $NS -- cqlsh -e "CREATE KEYSPACE foo WITH replication = {'class': 'NetworkTopologyStrategy','dc1':'2'};"
kubectl exec -it elassandra-cl1-dc1-0-0 -n $NS -- bash -l -c "for i in {1..$N}; do post foo/bar '{\"foo\":\"bar\"}'; done"

kubectl exec -it elassandra-cl1-dc1-0-0 -n $NS -- bash -l -c 'nodetool $NODETOOL_OPTS -p $NODETOOL_JMX_PORT flush'
kubectl exec -it elassandra-cl1-dc1-1-0 -n $NS -- bash -l -c 'nodetool $NODETOOL_OPTS -p $NODETOOL_JMX_PORT flush'
kubectl exec -it elassandra-cl1-dc1-2-0 -n $NS -- bash -l -c 'nodetool $NODETOOL_OPTS -p $NODETOOL_JMX_PORT flush'

# remove PVC protection for test
PVC=data-volume-elassandra-cl1-dc1-0-0
kubectl delete pvc $PVC --force --grace-period=0 &
kubectl patch pvc $PVC -p '{"metadata":{"finalizers": []}}' --type=merge

# delete pod to restart with an empty disk
kubectl delete pod elassandra-cl1-dc1-0-0
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN -r 3
kubectl exec -it elassandra-cl1-dc1-0-0 -n $NS -- bash -l -c "post _updaterouting"
sleep 5

TOTAL_HIT=$(kubectl exec -it elassandra-cl1-dc1-0-0 -n $NS -- bash -l -c "get 'foo/bar/_search?pretty'" | tail -n +4 | jq ".hits.total")
if [ "$TOTAL_HIT" != "$N" ]; then
   echo "Error, expecting $N docs in foo on dc1"
   finish
fi

uninstall_elassandra_datacenter $NS cl1 dc1
echo "### Test SUCCESSFUL"
test_end