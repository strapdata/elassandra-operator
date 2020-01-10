Operations
----------

Deploy a Datacenter
...................

.. note:: Before deploying the DataCenter, make sure that you have created cloud storage secrets to receive your backups (see `Backup & Restore <backup-restore.html#restore-your-cluster>`_)

To deploy an Elassandra datacenter you have to install the **elassandra-datacenter** helm chart with a *values* file adapted to your needs.
The release name of your chart must respect a naming convention in order to quickly identify a DC in your kubernetes environment. The name
must be composed of the cluster name and the datacenter name separated by a dash. (*clusterXYZ-datacenterXYZ*)

.. code-block:: bash

    helm install --namespace default --name mycluster-mydatacenter -f custom-values.yaml elassandra-datacenter



Datacenter status
.................

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


Elassandra node status
......................

In the same manner as the datacenter status (see `Datacenter status`_) you can access to nodes status through the datacenter CRD.

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

Scale Up a datacenter
.....................

The Elassandra operator manages the addition of new Elassandra instances to match the requirement defined into the DataCenter CRD.
Once you added new nodes into the kubernetes cluster, you can patch the DataCenter CRD with the following command.

.. code-block:: bash

   kubectl patch -n default elassandradatacenters elassandra-mycluster-mydatacenter --type merge --patch '{ "spec" : { "replicas" : 6 }}'

Until the number of replicas is reach the DataCenter phase will have the *SCALING_UP* value to end with *RUNNING* once all new nodes are up and running.


Scale Down a data center
........................

The Elassandra operator allows you to remove Elassandra instances to match the requirement defined into the DataCenter CRD.
If you want to reduce the number of nodes into the kubernetes cluster, you can patch the DataCenter CRD with the following command.

.. code-block:: bash

   kubectl patch -n default elassandradatacenters elassandra-mycluster-mydatacenter --type merge --patch '{ "spec" : { "replicas" : 6 }}'

Until the number of replicas is reach the DataCenter phase will have the *SCALING_DOWN* value to end with *RUNNING* once enough nodes are decommissioned.


Rollback CRD
.................

To update a Datacenter configuration, you have to change the datacenter CRD using the kubectl *patch* command.
When a new configuration can't be applied, for example when you request to mush CPU for an elassandra node, the elassandra pod will stay in a pending state and the datacenter status will enter in the *ERROR* phase.
The updated StatefulSet with this new configuration will enter in the  *SCHEDULING_PENDING* phase.

Here is a status example :

.. code-block:: bash
  status:
    cqlStatus: ESTABLISHED
    cqlStatusMessage: Connected to cluster=[cltest] with role=[elassandra_operator]
      secret=[elassandra-cltest/cassandra.elassandra_operator_password]
    elassandraNodeStatuses:
      elassandra-cltest-dc1-0-0: UNKNOWN
      elassandra-cltest-dc1-1-0: NORMAL
      elassandra-cltest-dc1-2-0: NORMAL
    joinedReplicas: 3
    keyspaceManagerStatus:
      keyspaces:
      - _kibana
      replicas: 3
    kibanaSpaces:
    - ""
    lastMessage: Unable to schedule Pod elassandra-cltest-dc1-0-0
    needCleanup: false
    phase: ERROR
    rackStatuses:
    - joinedReplicas: 1
      name: "0"
      phase: SCHEDULING_PENDING
    - joinedReplicas: 1
      name: "1"
      phase: RUNNING
    - joinedReplicas: 1
      name: "2"
      phase: RUNNING
    readyReplicas: 2
    reaperPhase: ROLE_CREATED
    replicas: 3

In this situation, updating the CRD is not enough because the operator will stop the reconciliation to preserve working nodes.

To solve this kind of issue, the operator keep the last successfully applied datacenter in order to rollback on the previous working configuration.

Here is the procedure to rollback to the previous stable datacener CRD:

* list pods
* create a port-forward to the operator pod in order to call the rollback endpoint
* request a rollback
* identify the pending pods and delete them

.. code-block:: bash
  # list pods
  kubectl get pods
  NAME                                                     READY   STATUS    RESTARTS   AGE
  elassandra-cltest-dc1-0-0                                0/2     Pending   0          13m
  elassandra-cltest-dc1-1-0                                2/2     Running   0          23m
  elassandra-cltest-dc1-2-0                                2/2     Running   0          19m
  strapkop-elassandra-operator-f9d4d4454-88pkm             1/1     Running   0          31m

  # create a port-forward to the operator pod
  kubectl port-forward strapkop-elassandra-operator-f9d4d4454-88pkm 8080:8080

  # request the rollback endpoint
  curl -vv -X POST "http://localhost:8080/datacenter/default/cltest/dc1/rollback"
  *   Trying 127.0.0.1...
  * Connected to localhost (127.0.0.1) port 8080 (#0)
  > POST /datacenter/default/cltest/dc1/rollback HTTP/1.1
  > Host: localhost:8080
  > User-Agent: curl/7.47.0
  > Accept: */*
  >
  < HTTP/1.1 202 Accepted
  < Date: Thu, 26 Dec 2019 16:15:07 GMT
  < connection: keep-alive
  < transfer-encoding: chunked
  <
  * Connection #0 to host localhost left intact

  # The Datacenter phase should be "UPDATING"
  kubectl get edc elassandra-cltest-dc1
  ...
  status:
    cqlStatus: ESTABLISHED
    cqlStatusMessage: Connected to cluster=[cltest] with role=[elassandra_operator]
      secret=[elassandra-cltest/cassandra.elassandra_operator_password]
    elassandraNodeStatuses:
      elassandra-cltest-dc1-0-0: UNKNOWN
      elassandra-cltest-dc1-1-0: NORMAL
      elassandra-cltest-dc1-2-0: NORMAL
    joinedReplicas: 3
    keyspaceManagerStatus:
      keyspaces:
      - _kibana
      replicas: 3
    kibanaSpaces:
    - ""
    lastMessage: ""
    needCleanup: false
    phase: UPDATING
    rackStatuses:
    - joinedReplicas: 1
      name: "0"
      phase: SCHEDULING_PENDING
    - joinedReplicas: 1
      name: "1"
      phase: RUNNING
    - joinedReplicas: 1
      name: "2"
      phase: RUNNING
    readyReplicas: 2
    reaperPhase: ROLE_CREATED
    replicas: 3

  # now, identify the pending pods ...
  kubectl get pods
  NAME                                                     READY   STATUS    RESTARTS   AGE
  elassandra-cltest-dc1-0-0                                0/2     Pending   0          13m
  elassandra-cltest-dc1-1-0                                2/2     Running   0          23m
  elassandra-cltest-dc1-2-0                                2/2     Running   0          19m
  strapkop-elassandra-operator-f9d4d4454-88pkm             1/1     Running   0          31m

  # ... and delete them
  kubectl delete pod elassandra-cltest-dc1-0-0

  # the pod will restart with the new Statefulset configuration
  kubectl get pods
  NAME                                                     READY   STATUS    RESTARTS   AGE
  elassandra-cltest-dc1-0-0                                2/2     Running   0          53s
  elassandra-cltest-dc1-1-0                                2/2     Running   0          23m
  elassandra-cltest-dc1-2-0                                2/2     Running   0          19m
  strapkop-elassandra-operator-f9d4d4454-88pkm             1/1     Running   0          31m

  # once all pods are Running, the datacenter status should be in the RUNNING phase
  kubectl get edc elassandra-cltest-dc1
  ...
  status:
    cqlStatus: ESTABLISHED
    cqlStatusMessage: Connected to cluster=[cltest] with role=[elassandra_operator]
      secret=[elassandra-cltest/cassandra.elassandra_operator_password]
    elassandraNodeStatuses:
      elassandra-cltest-dc1-0-0: NORMAL
      elassandra-cltest-dc1-1-0: NORMAL
      elassandra-cltest-dc1-2-0: NORMAL
    joinedReplicas: 3
    keyspaceManagerStatus:
      keyspaces:
      - _kibana
      replicas: 3
    kibanaSpaces:
    - ""
    lastMessage: Unable to schedule Pod elassandra-cltest-dc1-0-0
    needCleanup: false
    phase: RUNNING
    rackStatuses:
    - joinedReplicas: 1
      name: "0"
      phase: RUNNING
    - joinedReplicas: 1
      name: "1"
      phase: RUNNING
    - joinedReplicas: 1
      name: "2"
      phase: RUNNING
    readyReplicas: 3
    reaperPhase: ROLE_CREATED
    replicas: 3

.. note::
    Currently the datacenter copy is keep in memory, so if the operator pod restart this copy is lost. In this situation, the rollback endpoint return a **204** status code instead of **202**.
    In this situation, you can patch the CRD with new values and call *reconcile* endpoint instead of the *rollback* one

.. code-block:: bash

    curl -vv -X POST "http://localhost:8888/datacenter/default/cltest/dc1/reconcile"
    *   Trying 127.0.0.1...
    * Connected to localhost (127.0.0.1) port 8888 (#0)
    > POST /datacenter/default/cltest/dc1/reconcile HTTP/1.1
    > Host: localhost:8888
    > User-Agent: curl/7.47.0
    > Accept: */*
    >
    < HTTP/1.1 202 Accepted
    < Date: Mon, 30 Dec 2019 08:36:13 GMT
    < connection: keep-alive
    < transfer-encoding: chunked


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


Park Elassandra Pods
....................

In development environment, it an be useful to stop Elassandra pods without deleting persistent volume in order to free compute resources.
Elassandra operator allows this by  preserving the number of replicas in the DataCenterStatus and then settings the number of replicas of a statefulset to 0.

Once you want to restart Elassandra nodes, you only have to restore the statefulset replicas.

Here is how to "park" elassandra pods :

.. code-block:: bash
    $ kubectl patch elassandradatacenters elassandra-cl1-dc1 --type merge --patch '{ "spec" : { "parked" : "true"}}'

    $ # check that the datacenter and the statefulset/racks are in PARKED phase
    $ kubectl get -o yaml edc elassandra-cl1-dc1

Here is how to "unpark" elassandra pods :

.. code-block:: bash
    $ kubectl patch elassandradatacenters elassandra-cl1-dc1 --type merge --patch '{ "spec" : { "parked" : "false"}}'

    $ # check that the datacenter and the statefulset/racks are in UPDATING phase during the pods restart (or RUNNING if all pods are ready)
    $ kubectl get -o yaml edc elassandra-cl1-dc1


Update a password
.................

This feature isn't yet managed

Enable/Disable search
.....................



Upgrade Elassandra
..................



Kubernetes services
...................

The Elassandra Operator provisions a set of kubernetes services to access to the Running applications.
Here is the list of services, with _cn_ and _dcn_ respectively the cluster name and the data center name you have configured in the Datacenter CRD.

.. cssclass:: table-bordered

+-------------------------------------+------------------------------------------------------------------+
| Name                                | Description                                                      |
+=====================================+==================================================================+
| elassandra-<cn>-<dcn>               |  TODO |
+-------------------------------------+------------------------------------------------------------------+
| elassandra-<cn>-<dcn>-elasticsearch | TODO  |
+-------------------------------------+------------------------------------------------------------------+
| elassandra-<cn>-<dcn>-external      | TODO  |
+-------------------------------------+------------------------------------------------------------------+
| elassandra-<cn>-<dcn>-kibana-kibana | TODO  |
+-------------------------------------+------------------------------------------------------------------+
| elassandra-<cn>-<dcn>-reaper        | TODO  |
+-------------------------------------+------------------------------------------------------------------+
| elassandra-<cn>-<dcn>-seeds         | TODO  |
+-------------------------------------+------------------------------------------------------------------+
| myproject-elassandra-operator       | TODO  |
+-------------------------------------+------------------------------------------------------------------+