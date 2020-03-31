# Elassandra Kuberenetes Operator

## Design

ElassandraOperator basically run a perpetual watcher service (based on the k8s Watch API) to catch changes on any generic kubernetes resources.
When starting (or restarting), Elassandra operator gather a list of existing resources to initialize a map (a resource cache),
an emit events (added, changed, removed) in an rxJava subject when a change occurs according to the current cache.

## Reconciliation


consignes + k8s event -> reconciliation

Consignes:
* P0 config changes
* P2: seed changed (configmap without sts change)
* P1: scale up/down
* P0 park/unpark

K8s events
* sts event
* deployment event
* pod event
* node event



## Tasks

### Task processing

We don't need to persist status on each step because sequential tasks are idempotent.
=> Just flush the status in etcd at the end of the task, like a report.
=> Collect step results and update the final status to avoid concurrency on the status object.

init: populate status pod map
process sequentially or in parallel => collect results
finalize: update task status with pod map in etcd

* Parallel Task on all Elassandra nodes must update status in etcd at the end of the task (Flush, Snapshot, Restore, FastRepair, FastIndexRebuild)
* Sequential Task on all Elassandra nodes can update status in etcd at each step, allowing to recover from a failure (CleanUp, RemoveNode, SlowRepair, SlowIndexRebuild).
* Single step tack update status at the end (ReplicationMap)

### Task Locking

Task are mutually exclusive and processed sequentially in FIFO by the operator.

Per cluster work queue process DC and Task events in FIFO order on one thread (a RxJava serialized subject), 
ensuring mutual exclusion between cluster management operations.

Each reconciliation is identified by an operation stored in the DataCenterStatus.currentOperation.

### Task cancellation

A running task can be cancelled by deleting the task (stop a DcRebuild, an IndexRebuild, a Restore, a Repair).
Running task is storage in a CompositeDisposable cleared if task is deleted.
 

