#!/usr/bin/env bash

create_resource_group
create_vnet
create_aks_cluster 1
nodes_zone

init_helm

create_namespace cl1
install_elassandra_operator cl1
install_singlenode_elassandra_datacenter cl1 dc1