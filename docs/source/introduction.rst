Elassandra Operator
===================

The Elassandra Operator automate the deployment and management of Elassandra datacenters in Kubernetes clusters.
By reducing the complexity of running a Cassandra/Elassandra cluster, the Elassandra operator lets you focus on the desired configuration.

Elassandra Operator features:

  * `Kubernetes StatefulSet <https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/>`_ per Cassandra rack to ensure data consistency across cloud-provider availability zones.
  * Manage mutliple Cassandra datacenters in the same or different Kubernetes clusters.
  * Manage rolling configuration changes, rolling upgrade/downgrade of Elassandra, scale up/down Elassandra datacenters.
  * Deploy `Cassandra Reaper <http://cassandra-reaper.io/>`_ to run continuous Cassandra repairs.
  * Deploy multiple `Kibana <https://www.elastic.co/fr/products/kibana>`_ instances with a dedicated Elasticserach index in Elassandra.
  * Park/Unpark Elassandra datacenters (and associated Kibana and Cassandra reaper instances).
  * Expose Elassandra metrics for the `Prometheus Operator <https://prometheus.io/docs/prometheus/latest/querying/operators/>`_.
  * Publish public DNS names allowing Elassandra nodes to be reachable from the internet (Cassandra CQL and Elasticsearch REST API).
  * Automatically generates SSL/TLS certificates and strong passwords stored as Kubernetes secrets.
  * Create Cassandra roles and automatically grants the desired permissions on Cassandra keyspaces.
  * Automatically adjust the Cassandra Replication Factor for managed keyspaces, repair and cleanup after scale up/down.

Requirements
------------

* Kubernetes cluster 1.15 or newer.
* `ExternalDNS <https://github.com/kubernetes-sigs/external-dns>`_ to expose Elassandra datacenters to the internet world.
* `Prometheus-Operator <https://github.com/coreos/prometheus-operator>`_ to monitor your cluster
* A Kubernetes `Ingress Controller <https://kubernetes.io/docs/concepts/services-networking/ingress-controllers/>`_ to expose Kibana, Cassandra Reaper user interface.

How It works
------------

The Elassandra Operator extends the Kubernetes API by creating a Custom Resource Definition (CRD) defining a Cassandra/Elassandra datacenter
and creates a Kubernetes StatefulSet per Cassandra availibility zone identified by the label ``failure-domain.beta.kubernetes.io/zone``.

The Elassandra Operator can also deploy :
* One Cassandra Reaper pod per datacenter to achieve continuous Cassandra repair.
* Many Kibana deployment allowing to visualize data indexed in Elasticsearch.

.. image:: ./images/k8s-operator.jpg

Once Elassandra pods are deployed and running, the Elassandra operator interacts with the Elassandra nodes through JMX, CQLSH and HTTP
to execute administrative tasks like Cassandra repair, cleanup or upload snapshotted SSTables to a remote repository.
It also interact directly with the Elassandra container to properly manage keyspace replication factor and deploy Cassandra roles and associated permissions.

.. image:: ./images/elassandra-operatror-components.jpg


