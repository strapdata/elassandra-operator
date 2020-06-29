Advanced Services
*****************

Managed Keyspaces
=================

The Elassandra-Operator can manage Cassandra keyspace replication for you:

* Create keyspace if not exists, create Cassandra role and setup Cassandra permissions and Elasticsearch privileges.
* Adjust the replication factor and run automatic repair/cleanup when Elassandra nodes are added or removed, or when a datacenter is added or removed.
* Register the keyspace into Cassandra Reaper to schedule continuous repairs.

Like the `Elasticsearch index.auto_expand_replicas <https://www.elastic.co/guide/en/elasticsearch/reference/current/index-modules.html#dynamic-index-settings>`_
index settings, the Elassandra-Operator automatically adjust the keyspace replication factor to the desired number of copies and the current number of nodes in the datacenter:

To create a Cassandra role, the Elassandra operator retreives its password in a Kubernetes secret named ``elassandra-[cluster_name]-keyspace`` by default, with
a secret key equals to the role name or specified by the ``secretKey`` field, as shown below. Specify a ``secretName`` to use an alternate Kubernetes secret.

.. code::

    kubectl create secret generic elassandra-cl1-keyspaces -n mynamespace --from-literal=gravitee='xxxxxxx'

Specify a managed keyspace in your datacenter CRD as shown below:

.. code::

    ...
    managedKeyspaces:
      - keyspace: gravitee
        rf: 3
        role: gravitee
        login: true
        superuser: false
        secretKey: gravitee
        repair: true
        grantStatements:
          - "GRANT gravitee TO gravitee"

Check you keyspace is properly managed in the datacenter status:

.. code::

    status:
      ...
      keyspaceManagerStatus:
        keyspaces:
        - _kibana
        - gravitee

Continuous Cassandra repair
===========================

In order to ensure data consistency, a continuous cassandra repair can be managed by a `Cassandra Reaper <https://http://cassandra-reaper.io/>`_
instance running on each datacenter. The Elassandra-Operator automatically configure Cassandra Reaper,
register the Cassandra cluster and schedule repairs for managed keyspaces.

.. image:: ./images/reaper-cluster.png

Here is the datacenter spec to configure Cassandra reaper deployment:

.. jsonschema:: datacenter-spec.json#/properties/reaper

The Elassandra Operator deploys one Cassandra Reaper instance per datacenter
(see `Operating Multiple DCs using Multiple Reaper <http://cassandra-reaper.io/docs/usage/multi_dc_distributed/>`_),
with datacenterAvailability=EACH, JMX connections to local nodes only.

You can enable/disable the Cassandra Reaper deployment by updating the Elassandra datacenter spec as show in
the following example:

.. code::

    helm upgrade --reuse-values --set reaper.enabled=true default-cl1-dc1 strapdata/elassandra-datacenter

Address translation
-------------------

When your Elassandra/Cassandra cluster use Kubernetes node's public IP address,
application deployed in the same Kubernetes cluster using the Cassandra driver should use a
`Driver-side Address Translation <https://docs.datastax.com/en/developer/java-driver/3.7/manual/address_resolution/>`_ to connect to Elassandra/Cassandra nodes
using their internal IP.

This is the case of the deployed Cassandra Reaper instance which use Elassandra to store various data to manage continuous repairs,
but also for any application you may deploy in the same Kubernetes cluster.

So we provide an `AddressTranslator <https://docs.datastax.com/en/developer/java-driver/3.7/manual/address_resolution/>`_
implementation **KubernetesDnsAddressTranslator** using the DNS resolution to translate the public broadcast IP address to the internal IP address,
installed for Cassandra Reaper.

This **KubernetesDnsAddressTranslator** works in two ways:

* If the env variable ``ADDRESS_TRANSLATOR_DNS_DOMAIN`` is defined, the **KubernetesDnsAddressTranslator** convert the public address X.X.X.X
  to the DNS name X-X-X-X.$ADDRESS_TRANSLATOR_DNS_DOMAIN and try to resolve it to get the internal node IP address.
* If the ``ADDRESS_TRANSLATOR_DNS_DOMAIN`` is not defined or the resolution failed, the KubernetesDnsAddressTranslator try to
  revers lookup the public IP to get the internal Kubernetes node name. Then it resolves the Kubernetes node name to its internal IP address.

To achieve this behavior, you may need to deploy `CoreDNS <https://coredns.io/>`_ in your Kubernetes cluster,
with the `hosts plugin <https://coredns.io/plugins/hosts/>`_
properly configured to reverse lookup public IP addresses of Kubernetes nodes internal IP addresses.

Kibana visualisation
====================

In order to visualize your Elassandra data, or interact with Elasticsearch, the Elassandra-Operator can deploy
secured Kibana instances pointing to your Elassandra datacenter nodes.

.. image:: ./images/kibana-cluster-state.png

When Elasticsearch HTTPS is enabled in your Elassandra datacenter, Kibana is automatically configured to connect
through HTTPS and trust the Elassandra datacenter root CA. Moreover, for each Kibana space, the Elassandra-Operator
creates a dedicated Cassandra role and a dedicated managed keyspace storing the kibana configuration.
Thus, you can run separated kibana instances dedicated to specific usages or specific users.

Here is the datacenter spec to configure kibana deployment:

.. jsonschema:: datacenter-spec.json#/properties/elasticsearch/properties/kibana

You can enable/disable the Kibana deployment by updating the Elassandra datacenter spec as show in
the following example:

.. code::

    helm upgrade --reuse-values --set kibana.enabled=true default-cl1-dc1 strapdata/elassandra-datacenter

You can also adjust Kibana memory by adding the following podTemplate to set environment variables or customize resources:

.. code::

    kibana:
      spaces:
      - name: "default"
        podTemplate:
          spec:
            containers:
            - name: kibana
              env:
              - name: NODE_OPTIONS
                value: "--max-old-space-size=4096"