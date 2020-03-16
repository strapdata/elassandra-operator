#!/usr/bin/env bash
set -x
set -e

source $(dirname $0)/test-lib.sh

#create_resource_group
#create_aks_cluster 3


#helm_init

#install_elassandra_operator


install_elassandra_datacenter default cl1 dc1 1
java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING

#scale_elassandra_datacenter cl1 dc1 2
#java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING

#scale_elassandra_datacenter cl1 dc1 3
#java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING

park_elassandra_datacenter cl1 dc1
java -jar ../java/edctl/build/libs/edctl.jar watch -p PARKED
sleep 3

unpark_elassandra_datacenter cl1 dc1
java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING

exit

downgrade_elassandra_datacenter cl1 dc1
java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING

add_memory_elassandra_datacenter
java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING

scale_elassandra_datacenter cl1 dc1 1
java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING

uninstall_elassandra_datacenter

