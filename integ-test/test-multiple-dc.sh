#!/usr/bin/env bash

source $(dirname $0)/test-lib.sh

NS="ns2"

test_start
install_elassandra_datacenter $NS cl1 dc1 1
java -jar java/edctl/build/libs/edctl.jar watch-dc -n $NS --health GREEN

install_elassandra_datacenter default cl1 dc2 1
java -jar java/edctl/build/libs/edctl.jar watch-dc -n $NS --health GREEN

uninstall_elassandra_datacenter $NS cl1 dc1
uninstall_elassandra_datacenter $NS cl1 dc2

echo "Test SUCCESSFUL"
test_end