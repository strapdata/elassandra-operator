Quick start
===========

Deploy the Elassandra operator in the default namespace using HELM 2:

.. code::

    helm install --namespace default --name strapkop --wait helm/elassandra-operator

Deploy an Elassandra Datacenter in a dedicated namespace **ns1** with 1 replica:

.. code::

    helm install --namespace "ns1" --name "ns1-cl1-dc1" --set replicas=1 --wait helm/elassandra-datacenter

.. note:

    * To avoid mistakes, HELM release name MUST include the cluster name and datacenter name separated by a dash.
    * The default storageclass is **standard**, but your can use any available storageclass.
    * Cassandra reaper, Elasticsearch and Kibana are enable by default.

Check Elassandra pods status:

.. code::

    kubectl get pod -n ns1

Check the Elassandra DataCenter status:

.. code::

    kubectl get edc elassandra-cl1-dc1 -o yaml

List Elassandra datacenter secrets:

.. code::

    kubectl get secret -n ns1

Connect to a Cassandra node:

.. code::

    kubecrtl exec -it elassandra-cl1-dc1-0-0 -- bash -l

Connect to Kibana using port-forwarding:

.. code::

    kubectl port-forward pod/kibana 5601:5601

Alternatively, you can setup an ingress controller for the kibana instance.

Watch the Elassandra Datacenter CRD status until it is green, meaning all pods are up and running:

.. code::

    edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN



