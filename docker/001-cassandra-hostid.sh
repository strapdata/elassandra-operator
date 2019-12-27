#!/bin/bash
#
# Set Cassandra host id for seed node (index=0)
set -x

export POD_INDEX="${POD_NAME##*-}"

if [ "$POD_INDEX" == "0" ]; then
   JVM_OPTS="$JVM_OPTS -Dcassandra.host_id=$SEED_HOST_ID"
fi

