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

# usage:
#   config_injection CASSANDRA $CASSANDRA_CONFIG/cassandra.yaml
# or:
#   config_injection ELASTICSEARCH $CASSANDRA_CONFIG/elasticsearch.yml
config_injection() {

  local filename="$2";
  local filter
  local tempFile

    for v in $(compgen -v "${1}__"); do
     echo "v=$v"
     val="${!v}"
     if [ "$val" ]; then
        var=$(echo ${v#"${1}"}|sed 's/__/\./g')
        if is_num ${val}; then
          filter="${var}=${val}"
        else
          case ${val} in
            true)  filter="${var}=true";;
            false) filter="${var}=false";;
            *)     filter="${var}=\"${val}\"";;
          esac
        fi

        tempFile="$(mktemp)"
        if [[ "$(yq --yaml-output . $filename | wc -l | xargs)" == 0 ]]; then
            echo "${filter:1}" | sed 's/=/: /g' > "$tempFile"
        else
           yq --yaml-output ". * $(echo "${filter};" | gron -u)" $filename > "$tempFile"
        fi
        cat "$tempFile" > $filename
        rm "$tempFile"
     fi
    done

    if [ "$DEBUG" ]; then
       echo "config_injection $filename:"
       cat "$filename"
    fi
}

is_num() {
  re='^-?[0-9]+$'
  [[ $1 =~ $re ]] && true
}


# default settings
ES_USE_INTERNAL_ADDRESS=""
BROADCAST_ADDRESS="$POD_IP"
BROADCAST_RPC_ADDRESS="$POD_IP"


if [ -f "/nodeinfo/node-ip" ] && [ -s "/nodeinfo/node-ip" ]; then
    NODE_IP=$(cat /nodeinfo/node-ip)
    BROADCAST_ADDRESS=$NODE_IP
    BROADCAST_RPC_ADDRESS=$NODE_IP
    ES_USE_INTERNAL_ADDRESS="-Des.use_internal_address=true"
fi

if [ -f "/nodeinfo/public-ip" ] && [ -s "/nodeinfo/public-ip" ]; then
   PUBLIC_IP=$(cat /nodeinfo/public-ip)
   BROADCAST_RPC_ADDRESS=$PUBLIC_IP
   BROADCAST_ADDRESS=$PUBLIC_IP
   ES_USE_INTERNAL_ADDRESS="-Des.use_internal_address=true"
fi

if [ -f "/nodeinfo/public-name" ] && [ -s "/nodeinfo/public-name" ]; then
    PUBLIC_NAME=$(cat /nodeinfo/public-name)
    BROADCAST_ADDRESS="$PUBLIC_NAME"
    BROADCAST_RPC_ADDRESS="$PUBLIC_NAME"
    ES_USE_INTERNAL_ADDRESS="-Des.use_internal_address=true"
fi


# Define broadcast address
echo "broadcast_address: $BROADCAST_ADDRESS" > /etc/cassandra/cassandra.yaml.d/002-broadcast_address.yaml
echo "broadcast_rpc_address: $BROADCAST_RPC_ADDRESS" > /etc/cassandra/cassandra.yaml.d/002-broadcast_rpc_address.yaml

# Bind elasticsearch transport on POD ip
echo "transport.bind_host: $POD_IP" > /etc/cassandra/elasticsearch.yml.d/001-transport.yaml

if [ "x${STOP_AFTER_COMMILOG_REPLAY}" != "x" ]; then
  echo "REQUEST FOR cassandra.stop_after_commitlog_replayed : CassandraDeamon will stop after all commitlog will be replayed"
  export JVM_OPTS="$JVM_OPTS -Dcassandra.stop_after_commitlog_replayed=${STOP_AFTER_COMMILOG_REPLAY} "
fi

export JVM_OPTS="$JVM_OPTS $ES_USE_INTERNAL_ADDRESS"

# Generate /etc/cassandra/jmxremote.password
if [ -n "$JMX_PASSWORD" ]; then
   echo "cassandra $JMX_PASSWORD" > /etc/cassandra/jmxremote.password
   chmod 400 /etc/cassandra/jmxremote.password
fi

config_injection CASSANDRA $CASSANDRA_CONFIG/cassandra.yaml
config_injection ELASTICSEARCH $CASSANDRA_CONFIG/elasticsearch.yml

# handle kubernetes SIGTERM and gracefully stop elassandra
_term() {
  echo "entry-point: received SIGTERM"
  current_mode=$(nodetool ${NODETOOL_OPTS} netstats | grep "Mode" | head -n 1 | sed 's/^Mode: \(.*\)$/\1/g')
  echo "current mode is ${current_mode}"
  case "$current_mode" in
  NORMAL)
    echo "draining node..."
    nodetool ${NODETOOL_OPTS} drain
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