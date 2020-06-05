Configuration
-------------

Resources configuration
_______________________

You can adjust CPU and Memory needs of your Elassandra nodes by updating the CRD elassandradatacenter as shown here:

.. code::

    kubectl patch elassandradatacenter elassandra-cl1-dc1 --type merge --patch '{"spec":{"resources":{"limits":{"memory":"4Gi"}}}}'

Resources entry may receive "limits" and/or "requests" quantity description as describe in the `k8s documentation <https://kubernetes.io/docs/concepts/configuration/manage-compute-resources-container/>`_.

.. code::

    resources:
      requests:
        cpu: 500m
        memory: 1Gi
      limits:
        cpu: 1000m
        memory: 2Gi

Pod affinity
____________

You can define the `NodeAffinity <https://kubernetes.io/docs/concepts/configuration/assign-pod-node/#node-affinity>`_
for the Elassandra pods using the ``nodeAffinityPolicy`` attribute of the DatacenterSpec. Possible values are :

* STRICT : schedule elassandra pods only on nodes in the matching the ``failure-domain.beta.kubernetes.io/zone`` label (default value)
* SLACK : schedule elassandra pods preferably on nodes in the matching the ``failure-domain.beta.kubernetes.io/zone`` label

Of course, when ``hostNetwork`` or ``hostPort`` is enabled (see Networking), using the SLACK affinity is not possible because all Elassandra nodes
of a cluster listen on the same TCP ports.

Peristent Storage
-----------------

Elassandra nodes require persistent volumes to store Cassandra and Elasticsearch data.
You can use various kubernetes storage class including local and attached volumes.
Usage of SSD disks is recommended for better performances.

Persistent volume attached to availability zones
________________________________________________

The Elassandra operator deploys one Cassandra rack per availability zone to ensure data consistency when a zone is unavailable.
Each Cassandra rack is a Kubernetes StatefulSet, and rack names are Kubernetes node label ``failure-domain.beta.kubernetes.io/zone``.

In order to create Persistent Volume in the same availability zone as the StatefulSet,
you may create storage classes bound to availability zones of your cloud provider, as shown bellow using SSDs in GKE:

.. code::

    apiVersion: storage.k8s.io/v1
    kind: StorageClass
    metadata:
      name: ssd-europe-west1-b
      labels:
        addonmanager.kubernetes.io/mode: EnsureExists
        kubernetes.io/cluster-service: "true"
    provisioner: kubernetes.io/gce-pd
    parameters:
      type: pd-ssd
    allowVolumeExpansion: true
    reclaimPolicy: Delete
    volumeBindingMode: Immediate
    allowedTopologies:
      - matchLabelExpressions:
          - key: failure-domain.beta.kubernetes.io/zone
            values:
              - europe-west1-b

In the Elassandra datacenter spec, you can then specify a ``storageClassName`` ìncluding a **{zone}** variable replaced
by the corresponding availability zone name.

.. code::

    dataVolumeClaim:
      accessModes:
        - ReadWriteOnce
      storageClassName: "ssd-{zone}"
      resources:
        requests:
          storage: 128Gi

Peristent Volume decommission policy
____________________________________

By default, Elassandra nodes PVC are deleted when deleting an Elassandra datacenter, but you can keep PVCs with the following setting:

.. code::

    decommissionPolicy: KEEP_PVC

Network Configuration
---------------------

The Elassandra Operator can deploy datacenters in 3 networking configuration controlled by the following datacenter spec block:

.. code::

    networking:
      hostPortEnabled: false
      hostNetworkEnabled: false

In-cluster networking
_____________________

This is the default networking configuration where Cassandra and Elasticsearch pods listen on PODs private IP addresses.
In such configuration, Elassandra pods can only be reached by applications deployed in the same Kubernetes cluster through a headless service.

Out-of-cluster Networking with private IP addressing
____________________________________________________

In this configuration, Elassandra pods should be deployed with kubernetes ``hostPort`` enabled to allow the inbound traffic
on Elassandra ports (Cassandra Native and Storage, Elasticsearch HTTP/HTTPS port) from the outside of the Kubernetes cluster.

This allows Elassandra pod to bind and broadcast Kubernetes node private IP address to interconnect datacenters through VPN or PVC.

Out-of-cluster Networking with Public IP addressing
___________________________________________________

In this configuration, Elassandra pods broadcast a public IP should be deployed with ``hostNetwork`` enabled, allowing Elassandra pods
to bind and broadcast public IP address of their Kubernetes nodes. In such configuration, cross datacenter connection
can rely on public IP a``dresses without the need of a VPN or a VPC.

Managed Keyspaces
-----------------

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

Configuration
-------------

JVM settings
____________


Cassandra
_________

Here is the datacenter spec to configure cassandra:

.. jsonschema:: datacenter-spec.json#/properties/cassandra

Elasticsearch
_____________

Here is the datacenter spec to configure elasticsearch:

.. jsonschema:: datacenter-spec.json#/properties/elasticsearch

Kibana
______

In order to visualize your Elassandra data, or interact with Elasticsearch, the Elassandra-Operator can deploy
secured Kibana instances pointing to your Elassandra datacenter nodes.

When Elasticsearch HTTPS is enabled in your Elassandra datacenter, Kibana is automatically configured to connect
through HTTPS and trust the Elassandra datacenter root CA.

Moreover, for each kibana space, the Elassandra-Operator creates a dedicated Cassandra role and a dedicated managed keyspace storing the kibana configuration.
Thus, you can run separated kibana instances dedicated to specific usages or specific users.

Here is the datacenter spec to configure kibana deployment:


Continous Cassandra repair
__________________________

In order to ensure data consistency, a continuous cassandra repair can be managed by a `Cassandra Reaper <https://http://cassandra-reaper.io/>`_
instance running on each datacenter. The Elassandra-Operator automatically configure Cassandra Reaper, register the Cassandra cluster and schedule repairs for managed keyspaces.

Here is the datacenter spec to configure kibana deployment:

.. jsonschema:: datacenter-spec.json#/properties/reaper


Minimal values file for the Elassandra Operator helm chart.

.. code::

    apiVersion: elassandra.strapdata.com/v1
    image:
      repository: strapdata/elassandra-operator
      tag: 6.2.3.22
    imagePullPolicy: Always
    imagePullSecrets:
    resources: {}

Resource granted to the Operator may be defined through the resources section.

.. code::

    resources:
      # Suggested resource limits for the operator itself (not elassandra), works with a reasonable sized minikube.
      limits:
        cpu: 500m
        memory: 100Mi
      requests:
        cpu: 100m
        memory: 50Mi

Environment variables may also be define using the env section. Currently, only logging levels and the Operator Namespace are used.
The Operator namespace allows to define the namespace in which the operator will create resources, usually it is the namespace in which the operator is deployed.

.. code::

    env:
      OPERATOR_NAMESPACE: "default"

Through the environment variables it is possible to define the logging level using the following variables.

+--------------------------------------------+----------------------------------------------------------------------------------+
| Variable                                   |  Description                                                                     |
+============================================+==================================================================================+
| LOGBACK_com_strapdata_strapkop             | Root package of the Elassandra Operator                                          |
+--------------------------------------------+----------------------------------------------------------------------------------+
| LOGBACK_com_strapdata_strapkop_controllers | Control the logging level of REST Endpoint                                       |
+--------------------------------------------+----------------------------------------------------------------------------------+
| LOGBACK_com_strapdata_strapkop_k8s         |  Control the logging level of K8s rest client                                    |
+--------------------------------------------+----------------------------------------------------------------------------------+
| LOGBACK_com_strapdata_strapkop_event       | Control the logging level of event sources coming from K8s                       |
+--------------------------------------------+----------------------------------------------------------------------------------+
| LOGBACK_com_strapdata_strapkop_handler     | Control the logging level of classes that evaluate an event before processing it |
+--------------------------------------------+----------------------------------------------------------------------------------+
| LOGBACK_com_strapdata_strapkop_pipeline    | Control the logging level of event pipeline                                      |
+--------------------------------------------+----------------------------------------------------------------------------------+
| LOGBACK_com_strapdata_strapkop_sidecar     | Control the logging level of the sidecat client                                  |
+--------------------------------------------+----------------------------------------------------------------------------------+


Elassandra DataCenter
.....................

Elassandra configuration is generated by concatenating files from the following configuration sub-directories in /etc/cassandra:

* cassandra-env.sh.d
* cassandra.yaml.d
* elasticsearch.yml.d
* jvm.options.d

Files are loaded in alphanumeric order, so the last file overrides previous settings.

User configuration
__________________

You can add you own configuration file to Elassandra nodes by defining a Kubernetes configmap where each key is mapped to a file.
Here is an example to customize Cassandra settings from the cassandra.yaml file:

1. Create and deploy your user-config map:

.. code::

    apiVersion: v1
    kind: ConfigMap
    metadata:
      name: elassandra-cl1-dc1-user-config
      namespace: default
      labels:
        app: elassandra
        cluster: cl1
        datacenter: dc1
        parent: elassandra-cl1-dc1
    data:
      cassandra_yaml_d_user_config_overrides_yaml: |
        memtable_cleanup_threshold: 0.12

2. Patch the elassandraDatacenter CRD to map the user-config map to cassandra.yaml.d/009-user_config_overrides.yaml:

.. code::

    kubectl patch elassandradatacenter elassandra-cl1-dc1 --type merge --patch '{"spec":
        {"userConfigMapVolumeSource":
            {"name":"elassandra-cl1-dc1-user-config","items":[
                {"key":"cassandra_yaml_d_user_config_overrides_yaml","path":"cassandra.yaml.d/009-user_config_overrides.yaml"},
                {"key":"logback.xml","path":"logback.xml"}]
            }
        }
    }'

3. The Elassandra operator detects the CRD change and update per rack statefulsets.

.. CAUTION::

    If you patch the CRD with a wrong schema, the elassandra operator won't be able to parse and process it until you fix it.




Pod affinity
____________

You can define the the `NodeAffinity <https://kubernetes.io/docs/concepts/configuration/assign-pod-node/#node-affinity>`_ for the elassandra pods using the "nodeAffinityPolicy" attribute of the DatacenterSpec.

.. code::

    kubectl patch elassandradatacenter elassandra-cl1-dc1 --type merge --patch '{"spec":{"nodeAffinityPolicy": "STRICT"}}'

Possible values are :
* STRICT : schedule elassandra pods only on nodes in the matching the failure-domain.beta.kubernetes.io/zone label (default value)
* SLACK : schedule elassandra pods preferably on nodes in the matching the failure-domain.beta.kubernetes.io/zone label

Data Volume Claim
_________________

To specify the persistence characteristics for each Elassandra node, you can describe a `PersistentVolumeClaimSpec <https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.12/#persistentvolumeclaimspec-v1-core>`_ as "dataVolumeClaim" value.

.. code::

    dataVolumeClaim:
      accessModes:
        - ReadWriteOnce
      resources:
        requests:
          storage: 128Gi


Cassandra Seeds
...............

The Elassandra operator use a custom Cassandra seed provider using the following 3 parameters :

.. cssclass:: table-bordered

+----------------+----------------+-----------------------------------------------------------------------------+
| Parameter      | Env variable   | Description                                                                 |
+================+================+=============================================================================+
| seeds          | SEEDS          | Local seed addresses or DNS hostname.                                       |
+----------------+----------------+-----------------------------------------------------------------------------+
| remote_seeds   | REMOTE_SEEDS   | Remote datacenters seed addresses or DNS names.                             |
+----------------+----------------+-----------------------------------------------------------------------------+
| remote_seeders | REMOTE_SEEDERS | Remote elassandra operator web service URL providing remote seed addresses. |
+----------------+----------------+-----------------------------------------------------------------------------+

Empty parameters are replaced by the associated env variable if available.

Finally, if no seed addresses is found from theses parameters, the seed provider automatically add the broadcast address
to bootstrap the node.

.. TIP::

    The Elassandra operator expose one seed address per rack on the HTTP endpoint ``/seeds/{namespace}/{clusterName}/{datacenterName}``.
    This endpoint can be exposed to a remote Kubernetes cluster hosting a remote Elassandra datacenter by using the
    appropriate Kubernetes service.


External contact endpoints
..........................

The Elassandra operator can configure external DNS with public IP adresses of seeds nodes (pod 0 in each rack statefulsets):
* When pod-0 starts, the Elassandra sidecer updates the DNS record with the current public IP of the Kubernetes node.
* When the operator delete the datacenter, the active DNS plugin removes all DNS records from the external zone.

