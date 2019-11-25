Operations
----------

Elassandra Operator Deployment
..............................

The Elassandra operator can be beployed as a Kubernetes deployment, or useing the HELM chart **elassandra-opertor**.

In order to generate X509 certificates, the Elassandra operator requires a root CA certificate and private key stored as
Kubernetes secrets. If theses secrets does not exist when the operator is deployed, the operator automatically generates a self-signed
root CA certificate:

* Secret **ca-pub** contains the root CA certificate as a PEM file and PKCS12 keystore. (respectively named *cacert.pem* and *truststore.p12*)
* Secret **ca-key** contains the root CA private key in a PKCS12 keystore. (named *ca.key*)

.. code-block:: bash

    $ kubectl describe secrets ca-key
    Name:         ca-key
    Namespace:    default
    Labels:       app.kubernetes.io/managed-by=elassandra-operator
    Annotations:  <none>

    Type:  Opaque

    Data
    ====
    ca.key:  1801 bytes

    $ kubectl describe secrets ca-pub
    Name:         ca-pub
    Namespace:    default
    Labels:       app.kubernetes.io/managed-by=elassandra-operator
    Annotations:  <none>

    Type:  Opaque

    Data
    ====
    cacert.pem:      1204 bytes
    truststore.p12:  1258 bytes


Kubernetes deployment
_____________________

Create a YAML file including a Kubernetes deployment, and a service:

.. code::

Deploy into your Kubernetes cluster:

.. code::

    kubectl -f apply elassandra-operator.yml

HELM deployment
_______________

.. code::

    helm install --namespace default --name strapkop elassandra-operator

Deploy a Datacenter
...................

To deploy an Elassandra datacenter you have to install the **elassandra-datacenter** helm chart with a *values* file adapted to your needs.
The release name of your chart must respect a naming convention in order to quickly identify a DC in your kubernetes environment. The name
must be composed of the cluster name and the datacenter name separated by a dash. (*clusterXYZ-datacenterXYZ*)

.. code-block:: bash

    helm install --namespace default --name mycluster-mydatacenter -f custom-values.yaml elassandra-datacenter

When an Elassandra node starts, several init containers runs before starting Elassandra:

* The **increase-vm-max-map-count** tune the system for Elassandra.
* The **nodeinfo** init container retrieve information form the underlying node, including zone and public IP address if available.
* The **commitlogs** init container replay Cassandra commitlogs, flush and stop. Because replaying commitlogs may vary depending on commitlogs size on disk, the Kubernetes liveness probe may timeout and lead to an endless restart loop.


Get the datacenter status
.........................

The Elassandra Operator uses the elassandra-datacenter CRD to pilot the Elassandra nodes.
To determinate what is your datacenter state, you can check the **phase** entry in the **status** section of the CRD.

.. code-block:: bash

    kubectl get elassandradatacenters elassandra-mycluster-mydatacenter -o jsonpath="{$.status.phase}"

Here is the possible values :

+----------------+-----------------------------------------------------------+
| Phase          | Description                                               |
+================+===========================================================+
| CREATING       | Initial status when the DC is deployed for the first time |
+----------------+-----------------------------------------------------------+
| SCALING_DOWN   | The number of node inside you DC is downsizing            |
+----------------+-----------------------------------------------------------+
| SCALING_UP     | The number of node inside you DC is scaling up            |
+----------------+-----------------------------------------------------------+
| RUNNING        | This is the nominal state of your DC                      |
+----------------+-----------------------------------------------------------+
| UPDATING       | The operator is currently applying a newi DC configuration|
+----------------+-----------------------------------------------------------+
| EXECUTING_TASK | A task (ex: backup) is currently running by the operator  |
+----------------+-----------------------------------------------------------+
| ERROR          | An action encountered an error                            |
+----------------+-----------------------------------------------------------+

If the phase is set to *ERROR*, you can check the last error message with the **lastMessage** entry in the CRD status.

.. code-block:: bash

    kubectl get elassandradatacenters elassandra-mycluster-mydatacenter -o jsonpath="{$.status.lastMessage}"


Get the node status
...................

In the same manner as the datacenter status (see `Get the datacenter status`_) you can access to nodes status through the DataCenter CRD.

.. code-block:: bash

    kubectl get elassandradatacenters elassandra-mycluster-mydatacenter -o jsonpath="{$.status.elassandraNodeStatuses}"

This command will return a map of status for a given elassandra pod.

+----------------+------------------------------------------------------------------+
| Status         | Description                                                      |
+================+==================================================================+
| UNKNOWN        | The node is unreachable and the status is unknown                |
+----------------+------------------------------------------------------------------+
| STARTING       | The node is starting but isn't in a nominal state yet            |
+----------------+------------------------------------------------------------------+
| NORMAL         | The node is in nominal state                                     |
+----------------+------------------------------------------------------------------+
| JOINING        | The node is joining the cluster for the first time               |
+----------------+------------------------------------------------------------------+
| LEAVING        | The node is currently leaving the cluster                        |
+----------------+------------------------------------------------------------------+
| DECOMMISSIONED | The node was removed by administration action                    |
+----------------+------------------------------------------------------------------+
| MOVING         | The elassandra node is moving (may append only if num_token: 1)  |
+----------------+------------------------------------------------------------------+
| DRAINED        | A *nodetool drain* has been executed                             |
+----------------+------------------------------------------------------------------+
| DOWN           | temporary down due to a maintenance operation                    |
+----------------+------------------------------------------------------------------+
| FAILED         | failed to start or restart                                       |
+----------------+------------------------------------------------------------------+

Adjust Keyspace RF
..................

For managed keyspaces registered in the operator, the Cassandra Replication Factor can be automatically adjust according
to the desired number of replica and the number of available nodes.

Cassandra cleanup
.................

// TODO revoir cette section, en expliquant quand un clean up est triggé (à la fin d'un scaleup)

To execute a *nodetool cleanup* on each nodes, you have to create a **CleanUp task**.

.. code-block:: bash

    $ cat > cleanup-task.yaml << EOF
    apiVersion: stable.strapdata.com/v1
    kind: ElassandraTask
    metadata:
      name: "cleanup-task-001"
    spec:
      cluster: "mycluster"
      datacenter: "mydatacenter"
      cleanup: {}
    EOF
    $ kubectl create -n default -f cleanup-task.yaml

To check the status of the task :

.. code-block:: bash

    $ kubectl get elassandratasks
    NAME               AGE
    cleanup-task-001   76s

    $ kubectl get elassandratasks cleanup-task-001 -o yaml
    apiVersion: stable.strapdata.com/v1
    kind: ElassandraTask
    metadata:
      creationTimestamp: "2019-11-07T16:13:22Z"
      generation: 1
      name: cleanup-task-001
      namespace: default
      resourceVersion: "290120"
      selfLink: /apis/stable.strapdata.com/v1/namespaces/default/elassandratasks/cleanup-task-001
      uid: 345c5c85-377a-4d97-ad21-34457a2c7440
    spec:
      cleanup: {}
      cluster: mycluster
      datacenter: mydatacenter
    status:
      phase: SUCCEED
      pods:
        elassandra-mycluster-mydatacenter-local-0: SUCCEED

Update a password
.................

The Elassandra Operator defines a set of cassandra role when the DC

// list here the list of pwd and specify which one can be updated

To update a password used to interact with the Elassandra Cluster through CQL or JMX, you can update the values stored in the elassandra-*cluster_name* secret.

.. code-block:: bash

    $ kubectl get secret elassandra-mycluster -o json
    {
        "apiVersion": "v1",
        "data": {
            "cassandra.admin_password": "M2Q4NjRlNjktNGNkZi00NjVmLThhOTgtNjk0YjFiZThkYjQ3",
            "cassandra.cassandra_password": "YjA2OGY4OGEtMTdiOS00MjYxLTlmNjgtNTMxNzMyYjZjMTgz",
            "cassandra.jmx_password": "OTUwNjQ5MjYtYjNiOC00MTE4LTgzZTEtNjFhNzc0YzkwOGE4",
            "cassandra.reaper_password": "YTU5YWZhMjAtYzUyMi00ODIwLWI2YzQtNmFmMWUwMDEwZDU2",
            "cassandra.elassandra_operator_password": "NzllYzVkN2UtOWY1OS00YjAwLWIxMTctZDlhOTQ2NmJjOGFh",
            "shared-secret.yaml": "YWFhLnNoYXJlZF9zZWNyZXQ6IGQ2ZDliNGM5LTFkOGEtNDdhZi05ZDNkLTFhZDJiZmIwMzFkNQ=="
        },
        "kind": "Secret",
        "metadata": {
            "creationTimestamp": "2019-11-07T14:18:45Z",
            "labels": {
                "app": "elassandra",
                "app.kubernetes.io/managed-by": "elassandra-operator",
                "cluster": "mycluster"
            },
            "name": "elassandra-mycluster",
            "namespace": "default",
            "ownerReferences": [
                {
                    "apiVersion": "stable.strapdata.com/v1",
                    "blockOwnerDeletion": true,
                    "controller": true,
                    "kind": "ElassandraDataCenter",
                    "name": "elassandra-mycluster-mydatacenter",
                    "uid": "7e059ca9-1288-4643-be1d-2d25f99fb9ac"
                }
            ],
            "resourceVersion": "280541",
            "selfLink": "/api/v1/namespaces/default/secrets/elassandra-mycluster",
            "uid": "d16c480e-97eb-414b-87b0-23a6c3f6ba23"
        },
        "type": "Opaque"
    }

Once you have identified the password to update, you can run this command:

.. code-block:: bash

    kubectl get secret elassandra-mycluster -o json | jq --arg newpassword "$(echo -n pass1234 | base64)" '.data["cassandra.admin_password"]=$newpassword' | kubectl apply -f -


Enable/Disable search
.....................


Upgrade Elassandra
..................