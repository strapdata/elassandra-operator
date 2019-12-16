Elassandra Operator
===================

Elassandra Operator is a Kubernetes operator for Elassandra.

Elassandra Operator features
----------------------------

* Manage a Kuberentes statefulset of Elassandra nodes per cloud-provider zone to ensure high availability.
* Manage Cassandra seeds when datacenters are deployed on several Kuberenetes clusters.
* Generates SSL/TLS certificates for Elassandra nodes
* Manage backups and restores from/to your blobstore (S3, Azure, GCS)
* Automatically adjust the Cassandra replication factor for managed Keyspaces
* Update Cassandra passwords when a Kubernetes secret change.
* Create Cassandra roles and automatically grants the desired permissions on keyspaces.
* Manage Cassandra cleanup after a scale-up
* Allow to disable/enable Elasticsearch search on a node
* Copy Cassandra GC logs and Heapdump on your blogbstore for analysis
* Monitor the Elassandra JVM health and crash-it
* Efficiently expose Elassandra metrics for the prometheus-operator
* Deploy Cassandra reaper to ensure continuous Cassandra repairs.
* Deploy multiple Kibana instances with a dedicated index in Elassandra.


Getting started with helm
-------------------------

The Elassandra Operator is available under two helm charts, the first one install the operator and underlying resources (CRD, ServiceAccounts, Services...).
The second one is used to define the configuration of your Elassandra Datacenter thanks to a CustomResourceDefinition.

Firstly, deploy a pod with an operator in your kubrenetes cluster. To see the possible values of the helm chart see `Operator Configuration <configuration.html#elassandra-operator>`_

.. code-block:: bash

    helm install --namespace default --name myproject -f custom-values.yaml elassandra-operator

Check the pod is up and running.

.. code-block:: bash

      kubectl --namespace default get pods -l "app=elassandra-operator,release=myproject"

Finally, deploy the elassandra-datacenter CRD definition.

.. code-block:: bash

    helm install --namespace default --name mycluster-mydatacenter -f custom-values.yaml elassandra-datacenter

After a short period, you should have some elassandra running pods. You can list them using "elassandra" as value for the "app" label.

.. code-block:: bash

    kubectl --namespace default get pods -l "app=elassandra"
