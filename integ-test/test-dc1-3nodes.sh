#!/usr/bin/env bash

source $(dirname $0)/test-lib.sh

#create_resource_group
#create_aks_cluster 3

#helm_init
#install_elassandra_operator

test_start
install_elassandra_datacenter default cl1 dc1 1
java -jar java/edctl/build/libs/edctl.jar watch-dc --health GREEN -v

scale_elassandra_datacenter cl1 dc1 2
java -jar java/edctl/build/libs/edctl.jar watch-dc -p RUNNING -r 2 -v

scale_elassandra_datacenter cl1 dc1 3
java -jar java/edctl/build/libs/edctl.jar watch-dc -p RUNNING -r 3 -v


park_elassandra_datacenter cl1 dc1
java -jar java/edctl/build/libs/edctl.jar watch-dc -p PARKED -r 0 -v
sleep 10
unpark_elassandra_datacenter cl1 dc1
java -jar java/edctl/build/libs/edctl.jar watch-dc -p RUNNING --health GREEN -r 3 -v


reaper_enable cl1 dc1
java -jar java/edctl/build/libs/edctl.jar watch-dc --reaper REGISTERED -v
sleep 10
reaper_disable cl1 dc1
java -jar java/edctl/build/libs/edctl.jar watch-dc --reaper NONE -v

#downgrade_elassandra_datacenter cl1 dc1
#java -jar java/edctl/build/libs/edctl.jar watch-dc -p RUNNING

#add_memory_elassandra_datacenter
#java -jar java/edctl/build/libs/edctl.jar watch-dc -p RUNNING

#scale_elassandra_datacenter cl1 dc1 1
#java -jar java/edctl/build/libs/edctl.jar watch-dc -p RUNNING

uninstall_elassandra_datacenter
echo "Test SUCCESSFUL"
test_end