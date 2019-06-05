#!/bin/bash

sleep 30 # wait for elassandra to bootstrap

exec -a "cassandra-sidecar" java \
    ${JVM_OPTS} \
    -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap \
    -jar /opt/lib/cassandra-sidecar/cassandra-sidecar.jar \
    "$@"