#!/usr/bin/env bash

source integ-test/test-lib.sh
setup_flavor

NS="ns1"

test_start
install_elassandra_datacenter $NS cl1 dc1 1
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN

scale_elassandra_datacenter $NS cl1 dc1 2
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS -p RUNNING -r 2

scale_elassandra_datacenter $NS cl1 dc1 3
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS -p RUNNING -r 3


park_elassandra_datacenter $NS cl1 dc1
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS -p PARKED -r 0
sleep 10
unpark_elassandra_datacenter $NS cl1 dc1
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS -p RUNNING --health GREEN -r 3


reaper_enable $NS cl1 dc1
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --reaper REGISTERED
sleep 10
reaper_disable $NS cl1 dc1
java/edctl/build/libs/edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --reaper NONE -v

#downgrade_elassandra_datacenter cl1 dc1
#java/edctl/build/libs/edctl watch-dc -p RUNNING

#add_memory_elassandra_datacenter
#java/edctl/build/libs/edctl watch-dc -p RUNNING

#scale_elassandra_datacenter cl1 dc1 1
#java/edctl/build/libs/edctl watch-dc -p RUNNING

uninstall_elassandra_datacenter $NS cl1 dc1
echo "Test SUCCESSFUL"
test_end