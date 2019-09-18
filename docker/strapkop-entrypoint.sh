#!/bin/bash -xue

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

ES_USE_INTERNAL_ADDRESS=""
if [ -f "/nodeinfo/node-ip" ] && [ -s "/nodeinfo/node-ip" ]; then
    NODE_IP=$(cat /nodeinfo/node-ip)
    BROADCAST_ADDRESS=$NODE_IP
    BROADCAST_RPC_ADDRESS=$NODE_IP
    ES_USE_INTERNAL_ADDRESS="-Des.use_internal_address=true"
fi

if [ -f "/nodeinfo/public-ip" ] && [ -s "/nodeinfo/public-ip" ]; then
   PUBLIC_IP=$(cat /nodeinfo/public-ip)
   BROADCAST_ADDRESS=$PUBLIC_IP
   BROADCAST_RPC_ADDRESS=$PUBLIC_IP
   ES_USE_INTERNAL_ADDRESS="-Des.use_internal_address=true"
fi

# Define broadcast address
if [ -z "BROADCAST_ADDRESS" ]; then
  echo "warning during startup: BROADCAST_ADDRESS is not defined, POD_IP=$POD_IP NODE_IP=$NODE_IP PUBLIC_IP=$PUBLIC_IP" >&2
else
  echo "broadcast_address: $BROADCAST_ADDRESS" > /etc/cassandra/cassandra.yaml.d/002-broadcast_address.yaml
fi

# Define RPC broadcast address
if [ -z "BROADCAST_RPC_ADDRESS" ]; then
  echo "warning during startup: BROADCAST_RPC_ADDRESS is not defined, POD_IP=$POD_IP NODE_IP=$NODE_IP PUBLIC_IP=$PUBLIC_IP" >&2
else
  echo "broadcast_rpc_address: $BROADCAST_RPC_ADDRESS" > /etc/cassandra/cassandra.yaml.d/002-broadcast_rpc_address.yaml
fi
export JVM_OPTS="$ES_USE_INTERNAL_ADDRESS"

# Load node IPs of local seeds for the strapkop SeedProvider
if [ -f "/nodeinfo/seeds-ip" ] && [ -s "/nodeinfo/seeds-ip" ]; then
    export SEEDS_IP=$(cat /nodeinfo/seeds-ip)
    echo "SEEDS_IP=$SEEDS_IP"
fi

# Generate /etc/cassandra/jmxremote.password
if [ -n "$JMX_PASSWORD" ]; then
   echo "cassandra $JMX_PASSWORD\n" > /etc/cassandra/jmxremote.password
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

/usr/sbin/cassandra &
pid=$!
trap _term SIGTERM
wait ${pid}
echo "cassandra has exited"