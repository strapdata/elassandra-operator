#!/usr/bin/env bash

source $(dirname $0)/test-lib.sh

#create_resource_group
#create_aks_cluster 3
#helm_init
#install_elassandra_operator

test_start
install_elassandra_datacenter default cl1 dc1 1
java -jar ../java/edctl/build/libs/edctl.jar watch --health GREEN

install_elassandra_datacenter default cl1 dc2 1
java -jar ../java/edctl/build/libs/edctl.jar watch --health GREEN


#create_resource_group
#create_aks_cluster 3
#helm_init
#install_elassandra_operator

uninstall_elassandra_datacenter
echo "Test SUCCESSFUL"
test_end