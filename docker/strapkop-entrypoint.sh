#!/bin/bash -xe

source ./systune.sh

# overlay configuration from configmap mounted volumes into /etc/cassandra
(
for config_directory in "$@"
do
    # k8s configmap volumes are a mess of symlinks -- the find command cleans this up (skip ay dirs starting with ..)

    cd "${config_directory}"
    find -L . -name "..*" -prune -o \( -type f -print0 \) |
        cpio -pmdLv0 /etc/cassandra
done
)

# default settings
ES_USE_INTERNAL_ADDRESS=""
LISTEN_ADDRESS="$POD_IP"
BROADCAST_ADDRESS="$POD_IP"
BROADCAST_RPC_ADDRESS="$POD_IP"

if [ -f "/nodeinfo/node-ip" ] && [ -s "/nodeinfo/node-ip" ]; then
    NODE_IP=$(cat /nodeinfo/node-ip)
    BROADCAST_ADDRESS=$NODE_IP
    BROADCAST_RPC_ADDRESS=$NODE_IP
    ES_USE_INTERNAL_ADDRESS="-Des.use_internal_address=true"

    if [ "$HOST_NETWORK" == "true" ]; then
        LISTEN_ADDRESS=$NODE_IP
        echo "listen_address: $LISTEN_ADDRESS" > /etc/cassandra/cassandra.yaml.d/002-listen_address.yaml
    fi
fi

if [ -f "/nodeinfo/public-ip" ] && [ -s "/nodeinfo/public-ip" ]; then
   PUBLIC_IP=$(cat /nodeinfo/public-ip)
   BROADCAST_ADDRESS=$PUBLIC_IP
   BROADCAST_RPC_ADDRESS=$PUBLIC_IP
   ES_USE_INTERNAL_ADDRESS="-Des.use_internal_address=true"
fi

# Define broadcast address
echo "broadcast_address: $BROADCAST_ADDRESS" > /etc/cassandra/cassandra.yaml.d/002-broadcast_address.yaml
echo "broadcast_rpc_address: $BROADCAST_RPC_ADDRESS" > /etc/cassandra/cassandra.yaml.d/002-broadcast_rpc_address.yaml

export JVM_OPTS="$JVM_OPTS $ES_USE_INTERNAL_ADDRESS"

# Generate /etc/cassandra/jmxremote.password
if [ -n "$JMX_PASSWORD" ]; then
   echo "cassandra $JMX_PASSWORD" > /etc/cassandra/jmxremote.password
   chmod 400 /etc/cassandra/jmxremote.password
fi

# handle kubernetes SIGTERM and gracefully stop elassandra
_term() {
  echo "entry-point: received SIGTERM"
  current_mode=$(nodetool netstats | head -n 1 | sed 's/^Mode: \(.*\)$/\1/g')
  echo "current mode is ${current_mode}"
  case "$current_mode" in
  NORMAL)
    echo "draining node..."
    nodetool drain
    echo "drained"
    ;;
  *)
    echo "don't drain node before stop"
  esac
  kill -9 "$pid" 2>/dev/null
}

trap _term SIGTERM

/usr/sbin/cassandra &
pid=$!
wait ${pid}
echo "cassandra has exited"