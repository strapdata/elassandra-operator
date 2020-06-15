Introduction
============

The Elassandra Operator automates the deployment and management of Elassandra datacenters in Kubernetes clusters.
By reducing the complexity of running a Cassandra/Elassandra cluster, the Elassandra operator lets you focus on the desired configuration.

Elassandra Operator features:

  * `Kubernetes StatefulSet <https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/>`_ per Cassandra rack to ensure data consistency across cloud-provider availability zones.
  * Manage mutliple Cassandra datacenters in the same or different Kubernetes clusters.
  * Manage rolling configuration changes, rolling upgrade/downgrade of Elassandra, scale up/down Elassandra datacenters.
  * Deploy `Cassandra Reaper <https://cassandra-reaper.io/>`_ to run continuous Cassandra repairs.
  * Deploy multiple `Kibana <https://www.elastic.co/fr/products/kibana>`_ instances with a dedicated Elasticserach index in Elassandra.
  * Park/Unpark Elassandra datacenters (and associated Kibana and Cassandra Reaper instances).
  * Expose Elassandra metrics for the `Prometheus Operator <https://prometheus.io/docs/prometheus/latest/querying/operators/>`_.
  * Publish public DNS names allowing Elassandra nodes to be reachable from the internet (Cassandra CQL and Elasticsearch REST API).
  * Automatically generates SSL/TLS certificates and strong passwords stored as Kubernetes secrets.
  * Create Cassandra roles and automatically grants the desired permissions on Cassandra keyspaces.
  * Automatically adjust the Cassandra Replication Factor for managed keyspaces, repair and cleanup after scale up/down the datacenter.

Requirements
------------

* Kubernetes cluster 1.15 or newer.
* Kubernetes nodes must be properly synchronized with NTP.
* Kubernetes worker nodes must have labels matching ``failure-domain.beta.kubernetes.io/zone``.
* HELM version 2 for operator and datacenter deployment.
* `Prometheus-Operator <https://github.com/coreos/prometheus-operator>`_ to monitor your the Elassandra operator and Elassandra cluster.
* A Kubernetes `Ingress Controller <https://kubernetes.io/docs/concepts/services-networking/ingress-controllers/>`_ to expose Kibana, Cassandra Reaper user interface, and the Elassandra operator for cross kubernetes cluster discovery.
* `ExternalDNS <https://github.com/kubernetes-sigs/external-dns>`_ to expose Elassandra datacenters to the internet world.

How It works
------------

The Elassandra Operator extends the Kubernetes API by creating a Custom Resource Definition (CRD) defining a Cassandra/Elassandra datacenter
and creates a Kubernetes StatefulSet per Cassandra availability zone identified by the kubernetes label ``failure-domain.beta.kubernetes.io/zone``.

The Elassandra Operator can also deploy :
* One Cassandra Reaper pod per datacenter to achieve continuous Cassandra repair.
* Many Kibana deployment allowing to visualize data indexed in Elasticsearch and interact with Elasticsearch.

.. image:: ./images/elassandra-operator-overview.png

Once Elassandra pods are deployed and running, the Elassandra operator interacts with the Elassandra nodes through JMX, CQLSH and HTTP
to execute administrative tasks like Cassandra repair, cleanup or setup continuous repairs.
It also interact directly with the Elassandra container to properly manage keyspace replication factor and deploy Cassandra roles and associated permissions.

Multi-datacenter cluster
------------------------

The Elassandra operator can manage multiple Elassandra datacenters (or a Cassandra datacenter if Elasticsearch is disabled) in
one or multiple Kubernetes clusters. To achieve this, the Elassandra operator expose an HTTP endpoint **/seeds/{namespace}/{clusterName}/{dcName}**
returning the IP addresses of Elassandra seed pods (one seed per rack, the pod with ordinal index 0 in each statefulset), or their DNS names
when the HTTP request includes the parameter ``externalDNS=true``.

Thus, you can join datacenters having the same clusterName in the following configuration:

* When running in the same Kubernetes cluster, same namespace, the elassandra operator automatically join the datacenters together (the remoteSeeder is automatically set).
* When running in the same Kubernetes cluster, but in different namespaces, you need to specify a list of ``cassandra.remoteSeeders`` URLs
  in your datacenter spec, where each entry an URL of the form of https://elassandra-operator.default.svc/seeds/{remoteNamespace}/{clusterName}/{remoteDcName}

  For example, if you run datacenter **dc1** in the cluster **cl1** in namespace **ns1**, you need to deploy **dc2** in namespace **ns2** with the following remoteSeeders:

.. code::

  cassandra.remoteSeeders[0]=https://elassandra-operator.default.svc/seeds/ns1/cl1/dc1

* When running in different Kubernetes clusters, you need to run the Elassandra operator in each Kubernetes cluster
  with an ingress controller allowing elassandra nodes to request the remote operator **/seeds** endpoint to discover remote seeds IP addresses.

As show in the following figure, each Elassandra operator expose a **/seeds** endpoint returning the DNS names of seed nodes:

* When datacenters are in the same Kubernetes cluster, DNS names of seed nodes are internal DNS names.
* When running in distinct Kubernetes clusters, the Elassandra operator returns DNS names managed in a DNS zone updated by the ExternalDNS operator. On the Elassandra nodes, an init-container
  publishes a `DNSEndpoint <https://github.com/kubernetes-sigs/external-dns/blob/master/docs/contributing/crd-source.md>`_ manifest to
  register its IP address in the DNS zone (see the ExternalDNS block in the Elassandra datacenter spec).

.. image:: ./images/multi-dc-architecture.png