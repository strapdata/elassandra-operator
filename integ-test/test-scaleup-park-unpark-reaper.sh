#!/usr/bin/env bash

source $(dirname $0)/test-lib.sh

NS="ns1"

test_start
install_elassandra_datacenter $NS cl1 dc1 1
java -jar java/edctl/build/libs/edctl.jar watch-dc -n $NS --health GREEN -v

scale_elassandra_datacenter $NS cl1 dc1 2
java -jar java/edctl/build/libs/edctl.jar watch-dc -n $NS -p RUNNING -r 2 -v

scale_elassandra_datacenter $NS cl1 dc1 3
java -jar java/edctl/build/libs/edctl.jar watch-dc -n $NS -p RUNNING -r 3 -v


park_elassandra_datacenter $NS cl1 dc1
java -jar java/edctl/build/libs/edctl.jar watch-dc -n $NS -p PARKED -r 0 -v
sleep 10
unpark_elassandra_datacenter $NS cl1 dc1
java -jar java/edctl/build/libs/edctl.jar watch-dc -n $NS -p RUNNING --health GREEN -r 3 -v


reaper_enable $NS cl1 dc1
java -jar java/edctl/build/libs/edctl.jar watch-dc -n $NS --reaper REGISTERED -v
sleep 10
reaper_disable $NS cl1 dc1
java -jar java/edctl/build/libs/edctl.jar watch-dc -n $NS --reaper NONE -v

#downgrade_elassandra_datacenter cl1 dc1
#java -jar java/edctl/build/libs/edctl.jar watch-dc -p RUNNING

#add_memory_elassandra_datacenter
#java -jar java/edctl/build/libs/edctl.jar watch-dc -p RUNNING

#scale_elassandra_datacenter cl1 dc1 1
#java -jar java/edctl/build/libs/edctl.jar watch-dc -p RUNNING

uninstall_elassandra_datacenter $NS cl1 dc1
echo "Test SUCCESSFUL"
test_end