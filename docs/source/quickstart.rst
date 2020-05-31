Elassandra Operator
===================

The Elassandra Operator provides integration with Kubernetes to automate deployment and management of Elassandra datacenters.
By reducing the complexity of running a Cassandra/Elassandra cluster, the Elassandra operator lets you focus on the desired configuration.

Elassandra Operator features:

* Manage one `Kubernetes StatefulSet <https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/>`_ per Cassandra rack to ensure data consistency across cloud-provider zones.
* Manage mutliple cassandra datacenters in the same or different Kubernetes clusters.
* Manage rolling configuration changes, rolling upgrade/downgrade of Elassandra, scale up/down Elassandra datacenters.
* Park/Unpark a datacenters.
* Deploy `Cassandra Reaper <http://cassandra-reaper.io/>`_ to run continuous Cassandra repairs.
* Deploy multiple `Kibana <https://www.elastic.co/fr/products/kibana>`_ instances with a dedicated Elasticserach index in Elassandra.
* Expose Elassandra metrics for the `Prometheus Operator <https://prometheus.io/docs/prometheus/latest/querying/operators/>`_.
* Publish public DNS names allowing Elassandra nodes to be reachable from the internet.
* Automatically generates SSL/TLS certificates and strong passwords stored as Kubernetes secret.
* Create Cassandra roles and automatically grants the desired permissions on Cassandra keyspaces.
* Automatically adjust the Cassandra Replication Factor for managed keyspaces, and cleanup after scale up.

Quick start
-----------

Deploy the HELM 2 tiller pod:

.. code::

    helm init
    kubectl -n kube-system get po || helm init
    kubectl create serviceaccount --namespace kube-system tiller
    kubectl create clusterrolebinding tiller-cluster-rule --clusterrole=cluster-admin --serviceaccount=kube-system:tiller
    kubectl patch deploy --namespace kube-system tiller-deploy -p '{"spec":{"template":{"spec":{"serviceAccount":"tiller"}}}}'
    helm init --wait

Add the strapdata HELM repository:

.. code::

    helm repo add strapdata http://

Deploy the Elassandra operator in the default namespace. By default, The Elassandra opertor watch for Elassandra CRD in
all namespaces, but you can restrict it to the desired namepsace.

.. code::

    helm install --namespace default --name strapkop --wait elassandra-operator

Deploy an Elassandra datacenter CRD:

helm install --namespace "$ns" --name "$ns-$cl-$dc" \
    --set image.elassandraRepository=$REGISTRY_URL/strapdata/elassandra-node-dev \
    --set image.tag=$ELASSANDRA_NODE_TAG \
    --set dataVolumeClaim.storageClassName=${STORAGE_CLASS_NAME:-"standard"}$registry \
    --set elasticsearch.kibana.enabled="false" \
    --set reaper.enabled="false",reaper.image="$REGISTRY_URL/strapdata/cassandra-reaper:2.0.3" \
    --set cassandra.sslStoragePort="38001",jvm.jmxPort="35001",prometheus.port="34001" \
    --set externalDns.enabled="false",externalDns.root="xxxx.yyyy",externalDns.domain="test.strapkube.com" \
    --set replicas="$sz"$args \
    --wait \
    $HELM_REPO/elassandra-datacenter

Watch the Elassandra Datacenter CRD status until it is green, meaning all pods are up and running:

.. code::

    edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN


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


