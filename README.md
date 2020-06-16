# Elassandra Operator [![Build Status](https://travis-ci.com/strapdata/strapkop.svg?token=PzEdBQpdXSgcm2zGdxUn&branch=master)](https://travis-ci.com/strapdata/strapkop) [![Twitter](https://img.shields.io/twitter/follow/strapdataio?style=social)](https://twitter.com/strapdataio)

![Elassandra Logo](docs/source/images/elassandra-operator.png)

The Elassandra Kubernetes Operator automate the deployment and management of Elassandra datacenters in one or multiple Kubernetes clusters. 

## Features

* Elassandra Operator features:
  
  * Manage one `Kubernetes StatefulSet <https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/>`_ per Cassandra rack to ensure data consistency across cloud-provider availability zones.
  * Manage mutliple cassandra datacenters in the same or different Kubernetes clusters.
  * Manage rolling configuration changes, rolling upgrade/downgrade of Elassandra, scale up/down Elassandra datacenters.
  * Deploy `Cassandra Reaper <http://cassandra-reaper.io/>`_ to run continuous Cassandra repairs.
  * Deploy multiple `Kibana <https://www.elastic.co/fr/products/kibana>`_ instances with a dedicated Elasticserach index in Elassandra.
  * Park/Unpark Elassandra datacenters (and associated Kibana and Cassandra reaper instances).
  * Expose Elassandra metrics for the `Prometheus Operator <https://prometheus.io/docs/prometheus/latest/querying/operators/>`_.
  * Publish public DNS names allowing Elassandra nodes to be reachable from the internet (Cassandra CQL and Elasticsearch REST API).
  * Automatically generates SSL/TLS certificates and strong passwords stored as Kubernetes secrets.
  * Create Cassandra roles and automatically grants the desired permissions on Cassandra keyspaces.
  * Automatically adjust the Cassandra Replication Factor for managed keyspaces, repair and cleanup after scale up/down.

## Requirement

* Kubernetes cluster 1.15 or newer
* External-DNS to expose your datacenter to the internet world
* Prometheus-Operator to monitor your cluster
* An ingress controller to expose Kibana, Cassandra Reaper interfaces.

## Quick start

Deploy the Elassandra operator in the default namespace using HELM 2:

    helm install --namespace default --name strapkop --wait helm/elassandra-operator

Deploy an Elassandra Datacenter in a dedicated namespace **ns1** with 1 replica:

    helm install --namespace "ns1" --name "ns1-cl1-dc1" --set replicas=1 --wait helm/elassandra-datacenter

Notes:
* To avoid mistakes, HELM release name MUST include the cluster name and datacenter name separated by a dash.
* The default storageclass is **standard**, but your can use any available storageclass.
* Cassandra reaper, Elasticsearch and Kibana are enable by default.

Check Elassandra pods status:

    kubectl get pod -n ns1

Check the Elassandra DataCenter status:

    kubectl get edc elassandra-cl1-dc1 -o yaml

List Elassandra datacenter secrets:

    kubectl get secret -n ns1

Connect to a Cassandra node:

    kubecrtl exec -it elassandra-cl1-dc1-0-0 -- bash -l

Connect to Kibana using port-forwarding:

    kubectl port-forward pod/kibana 5601:5601

Alternatively, you can setup an ingress controller for the kibana instance.

## Public Elassandra Datacenter

If your Kubernetes nodes have public IP addresses, your can expose Elassandra nodes to the internet by using 
the hostNetwork mode of Kubernetes. In this case, each Elassandra cluster must have a dedicated set of TCP ports,
and a Kubernetes node can run more than one pod of a given Elassandra cluster.

    networking.hostNetwork: true

When hostNetwork or hostPort is enabled, the Elassandra operator enable an init container (named nodeinfo) to get 
the Kubernetes node public IP address and use it as the Cassandra broadcast address. 

You can then enabled external DNS configuration to automatically publish public DNS names for Elassandra nodes.

## Build from source

Publish the docker images (operator + elassandra + cassandra reaper):
```bash
./gradlew dockerPush -PregistryUsername=$DOCKER_USERNAME -PregistryPassword=$DOCKER_PASSWORD -PregistryUrl=$DOCKER_URL
```

Publish in local insecure registry
```bash
./gradlew dockerPush -PregistryUrl="localhost:5000" -PregistryInsecure
```

Build parameters are located in `gradle.properties`.

## License Report

Generate a [license report](build/reports/dependency-license/index.html):
```bash
./gradlew generateLicenseReport
```

## License

