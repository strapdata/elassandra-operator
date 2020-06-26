#!/usr/bin/env bash

source integ-test/test-lib.sh
setup_flavor

NS="reaper1"
NAMESPACES=("$NS")

test_start $0
install_elassandra_datacenter $NS cl1 dc1 3
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN -r 3 --cql-status=ESTABLISHED
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --reaper-unregistered
test "$(kubectl get edc elassandra-cl1-dc1 -n $NS -o jsonpath='{.status.reaperRegistred}')" == "false"

reaper_enable $NS cl1 dc1
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --reaper-registered
sleep 10
reaper_disable $NS cl1 dc1
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --reaper-unregistered

uninstall_elassandra_datacenter $NS cl1 dc1
echo "### Test SUCCESSFUL"
test_end