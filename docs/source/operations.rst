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

.. warning:: Before deploying the operator, if you want to use backup/restore feature you have to create secrets according to the cloud storage you will use, see `Backup & Restore <backup-restore.html#restore-your-cluster>`_ for more details.

Kubernetes deployment
_____________________

Create a YAML file including a Kubernetes deployment, and a service:

.. code::

   todo

Deploy into your Kubernetes cluster:

.. code::

    kubectl -f apply elassandra-operator.yml

HELM deployment
_______________

.. code::

    helm install --namespace default --name strapkop elassandra-operator

Deploy a Datacenter
...................

.. note:: Before deploying the DataCenter, make sure that you have created cloud storage secrets to receive your backups (see `Backup & Restore <backup-restore.html#restore-your-cluster>`_)

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

.. note:: ScalingDown a cluster isn't yet managed by the operator

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

Scale Up a data center
......................

The Elassandra operator manages the addition of new Elassandra instances to match the requirement defined into the DataCenter CRD.
Once you added new nodes into the kubernetes cluster, you can patch the DataCenter CRD with the following command.

.. code-block:: bash

   kubectl patch -n default elassandradatacenters elassandra-mycluster-mydatacenter --type merge --patch '{ "spec" : { "replicas" : 6 }}'

Until the number of replicas is reach the DataCenter phase will have the *SCALING_UP* value to end with *RUNNING* once all new nodes are up and running.

Cassandra cleanup
.................

When a cassandra cluster scale up, you have to cleans up keyspaces and partition keys no longer belonging to a node.
On on-premises instances, a *nodetool cleanup* on each nodes is required. The Elassandra Operator will trigger this clean up for you
once the number of replicas specify in the DataCenterSpec is reached.

If you want execute a cleanup by yourself,  you have to create a **CleanUp task**.

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

Scale Down a data center
........................

This feature isn't yet managed

Update a password
.................

This feature isn't yet managed

Enable/Disable search
.....................


Upgrade Elassandra
..................