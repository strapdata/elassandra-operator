Elassandra Operator
===================

The Elassandra Operator provides integration with Kubernetes to automate deployment and management of Elassandra Enterprise datacenters.
By reducing the complexity of running a Cassandra/Elassandra cluster, the Elassandra operator lets you focus on the desired configuration.

.. node::

    The Elassandra Operator only runs Elassandra Enterprise cluster having a valid license.

Elassandra Operator features:

* Manage one `Kubernetes StatefulSet <https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/>`_ per Cassandra rack to ensure data consistency across cloud-provider zones.
* Manage rolling configuration change, rolling upgrade/downgrade of Elassandra, scale up/down Elassandra datacenters, rolling cleanup after scale up.
* Deploy `Cassandra Reaper <http://cassandra-reaper.io/>`_ to run continuous Cassandra repairs.
* Deploy multiple `Kibana <https://www.elastic.co/fr/products/kibana>`_ instances with a dedicated Elasticserach index in Elassandra.
* Efficiently expose Elassandra metrics for the `Prometheus Operator <https://prometheus.io/docs/prometheus/latest/querying/operators/>`_.
* Manage Cassandra seeds when datacenters are deployed on several Kubernetes clusters.
* Automatically generates SSL/TLS certificates.
* Update Cassandra passwords when a Kubernetes secret change.
* Manage Cassandra backups and restores from/to your blobstore (S3, Azure, GCS)
* Automatically adjust the Cassandra Replication Factor for managed keyspaces.
* Create Cassandra roles and automatically grants the desired permissions on Cassandra keyspaces.
* Automatically disable/enable Elasticsearch search on a node during maintenance operation.

How It works
------------

The Elassandra Operator extends the Kubernetes API by creating a Custom Resource Definition (CRD) defining a Cassandra/Elassandra datacenter
and registering a custom Elassandra controller to manage one Kubernetes StatefulSet per Cassandra rack. To ensure data consistency in case of a zone failure,
all Elassandra pods of a Cassandra rack are pinned to Kubernetes nodes located in an availability zone.

The Elassandra Operator can also deploy :
* One Cassandra Reaper deployment to achieve continuous repair in the datacenter.
* Many Kibana deployment allowing to visualize data indexed in Elasticsearch.

.. image:: ./images/k8s-operator.jpg

Once Elassandra pods are deployed and running, the Elassandra operator interacts through an HTTPS connection with the Elassandra sidecar container
to periodically check the Elassandra status and execute administrative tasks like Cassandra cleanup or upload snapshotted SSTables to a remote repository.
It also interact directly with the Elassandra container to properly manage keyspace replication factor and deploy Cassandra roles and associated permissions.

.. image:: ./images/elassandra-operatror-components.jpg


