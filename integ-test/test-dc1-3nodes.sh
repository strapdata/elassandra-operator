#!/usr/bin/env bash
set -x
set -e

source $(dirname $0)/test-lib.sh

#create_resource_group
#create_aks_cluster 3


#helm_init

#install_elassandra_operator


install_elassandra_datacenter default cl1 dc1 1
java -jar ../java/edctl/build/libs/edctl.jar watch --health GREEN

#scale_elassandra_datacenter cl1 dc1 2
#java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING -r 2

#scale_elassandra_datacenter cl1 dc1 3
#java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING -r 3

park_elassandra_datacenter cl1 dc1
java -jar ../java/edctl/build/libs/edctl.jar watch -p PARKED -r 0
sleep 5

unpark_elassandra_datacenter cl1 dc1
java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING --health GREEN

exit

downgrade_elassandra_datacenter cl1 dc1
java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING

add_memory_elassandra_datacenter
java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING

scale_elassandra_datacenter cl1 dc1 1
java -jar ../java/edctl/build/libs/edctl.jar watch -p RUNNING

uninstall_elassandra_datacenter

