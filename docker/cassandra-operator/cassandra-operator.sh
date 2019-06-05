#!/bin/bash

exec -a "cassandra-operator" java \
    -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap \
    ${JVM_OPTS} \
    -jar /opt/lib/cassandra-operator/cassandra-operator.jar \
    "$@"