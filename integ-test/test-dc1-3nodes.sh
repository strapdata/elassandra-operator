#!/usr/bin/env bash

#create_resource_group
#create_aks_cluster 3


helm_init

install_elassandra_operator

install_elassandra_datacenter default cl1 dc1 1

scale_elassandra_datacenter cl1 dc1 2
