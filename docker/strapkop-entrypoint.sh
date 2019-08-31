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

if [ -f "/nodeinfo/public-ip" ]; then
   NODE_IP=$(cat /nodeinfo/public-ip)
fi

# In order to bind rpc to 0.0.0.0, broadcast_rpc_address must be set explicitly, and it can only be done at runtime.
# BROADCAST_RPC_ADDRESS = NODE_IP if defined, otherwise POD_IP
BROADCAST_RPC_ADDRESS=${NODE_IP:-$POD_IP}
if [ -z "BROADCAST_RPC_ADDRESS" ]; then
  echo "warning during startup: BROADCAST_RPC_ADDRESS is not defined, POD_IP=$POD_IP NODE_IP=$NODE_IP" >&2
else
  echo "broadcast_rpc_address: $BROADCAST_RPC_ADDRESS" > /etc/cassandra/cassandra.yaml.d/002-broadcast_rpc_address.yaml
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