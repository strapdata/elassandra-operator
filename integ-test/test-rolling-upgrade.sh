#!/usr/bin/env bash
# kb get pod -l app=elassandra -o jsonpath="{.items[*].spec.containers[*].image}"
# kubectl get pod -l app=elassandra '-o=custom-columns=NAME:.metadata.name,IMAGE:.spec.containers[*].image'

# $1 = image tag
function upgrade() {
  kubectl patch elassandradatacenter elassandra-cl1-dc1 -n $NS --type="merge" --patch "{\"spec\": { \"elassandraImage\": \"$REGISTRY_URL/strapdata/elassandra-node-dev:$1\" }}"
  sleep 1
  for i in {1..40}; do
    sleep 5
    java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN -r 5 --min-replicas 4
    if [ $? != 0 ]; then
      echo "ERROR: min available replicas lower than 5"
      exit 1
    fi
    x=$(kubectl get pod -n $NS -l app=elassandra '-o=custom-columns=IMAGE:.spec.containers[*].image' --no-headers | grep -v "$1" | wc -l | sed -e 's/ //g')
    if [ "$x" == "0" ]; then
        break
    fi
  done

  x=$(kubectl get pod -n $NS -l app=elassandra '-o=custom-columns=IMAGE:.spec.containers[*].image' --no-headers | grep -v "$1" | wc -l | sed -e 's/ //g')
  if [ "$x" != "0" ]; then
    echo "ERROR: $x pods does not run the target tag=$1"
    exit 1
  fi
}

NS=${NS:-"ns6"}
HELM_RELEASES=("$NS-cl1-dc1")
NAMESPACES=("$NS")

source integ-test/test-lib.sh
setup_flavor

test_start $0
install_elassandra_datacenter $NS cl1 dc1 5
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN -r 5 --cql-status=ESTABLISHED --timeout 900

upgrade "6.2.3.28"
upgrade "6.8.4.5"

# cleanup
uninstall_elassandra_datacenter $NS cl1 dc1
echo "### Test SUCCESSFUL"
