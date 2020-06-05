Operations
----------

Check the datacenter status
___________________________

You can check the Elassandra datacenter status as follow:

.. code::

    kubectl get elassandradatacenters elassandra-cl1-dc1 -o yaml

Scale Up/Down a datacenter
__________________________

You can scale up or scale down a datacenter by setting the ``replicas`` attribute in the datacenter spec.

.. code-block:: bash

   kubectl patch -n default elassandradatacenters elassandra-mycluster-mydatacenter --type merge --patch '{ "spec" : { "replicas" : 6 }}'

Park/Unpark a datacenter
________________________

You can park/unpark all pods of an Elassandra datacenter by updating the boolean ``parked`` attribute in the datacenter spec.

.. code-block:: bash

    kubectl patch elassandradatacenters elassandra-cl1-dc1 --type merge --patch '{ "spec" : { "parked" : "true"}}'

To "unpark" an Elassandra datacenter :

.. code-block:: bash

    kubectl patch elassandradatacenters elassandra-cl1-dc1 --type merge --patch '{ "spec" : { "parked" : "false"}}'

Elassandra Tasks
----------------

The Elassandra operators adds an ElassandraTask CRD allowing to manage administration tasks on your Elassandra datacenter.
With these task, you can properly automate adding or removing an Elassandra datacenter from an Elassandra cluster running in one or multiple
Kubenetes clusters.

Repair
______

The **repair** task sequentially runs a **nodetool repair** on all nodes of a datacenter, with waiting by default 10s between each cleanup.

.. code::

    cat <<EOF | kubectl apply -f -
    apiVersion: elassandra.strapdata.com/v1
    kind: ElassandraTask
    metadata:
      name: cleanup-task-$$
    spec:
      cluster: "cl1"
      datacenter: "dc1"
      cleanup: {}
    EOF

Cleanup
_______

The **cleanup** task sequentially runs a **nodetool cleanup** on all nodes of a datacenter, with waiting by default 10s between each cleanup.

.. code::

    cat <<EOF | kubectl apply -f -
    apiVersion: elassandra.strapdata.com/v1
    kind: ElassandraTask
    metadata:
      name: cleanup-task-$$
    spec:
      cluster: "cl1"
      datacenter: "dc1"
      cleanup: {}
    EOF

Replication
___________

The **replication** task adds or removes a datacenter in the Cassandra schema by updating keyspace replication map.
The following replication task adds the datacenter dc2 in the replication maps of system keyspaces and the **foo** user keyspace.

.. code::

    cat <<EOF | kubectl apply -f -
    apiVersion: elassandra.strapdata.com/v1
    kind: ElassandraTask
    metadata:
      name: replication-add-$$
      namespace: $NS
    spec:
      cluster: "cl1"
      datacenter: "dc1"
      replication:
        action: ADD
        dcName: "dc2"
        dcSize: 1
        replicationMap:
          foo: 1
    EOF

Rebuild
_______

The **rebuild** task runs a nodetool rebuild on all nodes of a datacenter in order to stream the data from another existing datacenter.
The following rebuild task rebuild the datacenter **dc2** by streaming data from the datacenter **dc1**.

.. code::

    cat <<EOF | kubectl apply -f -
    apiVersion: elassandra.strapdata.com/v1
    kind: ElassandraTask
    metadata:
      name: rebuild-dc2-$$
      namespace: $NS
    spec:
      cluster: "cl1"
      datacenter: "dc2"
      rebuild:
        srcDcName: "dc1"
    EOF

Update routing
______________

The **updateRouting** task updates the Elasticsearch routing table for all nodes of an Elassandra datacenter.
This is usually done after a datacenter rebuild when data becomes available to properly open elasticsearch indices.

.. code::

    cat <<EOF | kubectl apply -f -
    apiVersion: elassandra.strapdata.com/v1
    kind: ElassandraTask
    metadata:
      name: updaterouting-dc2-$$
      namespace: $NS
    spec:
      cluster: "cl1"
      datacenter: "dc2"
      updateRouting: {}
    EOF

Remove nodes
____________

The **removeNodes** task runs a nodetool removenode for all nodes of a deleted datacenter.
This is usually done after a datacenter is deleted and after replication for that datacenter has been remove with a ``replication`` task.

The following task is executed on one node of the datacenter **dc1** to remove all nodes from the datacenter **dc2**.

.. code::

    cat <<EOF | kubectl apply -f -
    apiVersion: elassandra.strapdata.com/v1
    kind: ElassandraTask
    metadata:
      name: removenodes-dc2-$$
      namespace: $NS
    spec:
      cluster: "cl1"
      datacenter: "dc1"
      removeNodes:
        dcName: "dc2"
    EOF

Edctl utility
-------------

The **edctl** utility (Elassandra Datacenter Ctl) allow to synchronously wait for status condition on Elassandra Datacenters and Tasks.