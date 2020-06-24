#!/usr/bin/env bash

CTX1=$1
CTX2=$2
CL=$3
DC1=$4
DC2=$5

cat <<EOF | kubectl apply --context $CTX1 -f -
apiVersion: elassandra.strapdata.com/v1beta1
kind: ElassandraTask
metadata:
  name: replication-add-$$
  namespace: default
spec:
  cluster: "$CL"
  datacenter: "$DC1"
  replication:
    action: ADD
    dcName: "$DC2"
    dcSize: 1
    replicationMap:
      elastic_admin: 1
      reaper_db: 1
      _kibana_1: 1
EOF
edctl watch-task --context $CTX1 -n replication-add-$$ -ns default --phase SUCCEED

cat <<EOF | kubectl apply --context $CTX2 -f -
apiVersion: elassandra.strapdata.com/v1beta1
kind: ElassandraTask
metadata:
  name: rebuild-$DC2-$$
  namespace: default
spec:
  cluster: "$CL"
  datacenter: "$DC2"
  rebuild:
    srcDcName: "$DC1"
EOF
edctl watch-task --context $CTX2 -n rebuild-$DC2-$$ -ns default --phase SUCCEED

cat <<EOF | kubectl --context $CTX2 apply -f -
apiVersion: elassandra.strapdata.com/v1beta1
kind: ElassandraTask
metadata:
  name: updaterouting-$DC2-$$
  namespace: default
spec:
  cluster: "$CL"
  datacenter: "$DC2"
  updateRouting: {}
EOF
edctl watch-task --context $CTX2 -n updaterouting-$DC2-$$ -ns default --phase SUCCEED