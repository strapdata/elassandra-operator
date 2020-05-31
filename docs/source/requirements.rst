Requirements
------------

Kubernetes requirements
.......................

To install the Elassandra Operator, all you need is a running Kubernetes version 1.15+.

Kubernetes node requirements
............................

The Elassandra operator use node labels to identify the availability zone the node belongs to. Here is a the labels
used for various cloud providers.

.. cssclass:: table-bordered

+------------+------------------------------------------------------------------+
| Name       | Description                                                      |
+============+==================================================================+
| Azure      |  failure-domain.beta.kubernetes.io/zone                          |
+------------+------------------------------------------------------------------+
| AWS        |  failure-domain.beta.kubernetes.io/zone                          |
+------------+------------------------------------------------------------------+
| GCP        |  failure-domain.beta.kubernetes.io/zone                          |
+------------+------------------------------------------------------------------+

By default, for a given datacenter, the Elassandra operator deploy one Elassandra pod per Kubernetes node.
Of course, to avoid the `noisy neighbor problem <https://en.wikipedia.org/wiki/Cloud_computing_issues#Performance_interference_and_noisy_neighbors>`_
it is recommended to run each Elassandra datacenter on a dedicated Kubernetes nodepool.

It is recommended that the following services be running on all Kubernetes nodes :

NTP
___

Like all distributed systems, Elassandra needs to have synchronized time in order to resolve conflicts.
Newer systems should have NTP enabled by default if running systemd-timesyncd.

Kernel Parameters
_________________

The recommended kernel parameters can be set via an orchestration solution or a Kubernetes DaemonSet resource:

* **vm.swappiness**

Memory requirement
..................

An Elassandra pod requires at least about 2Gb of RAM and 1.2Gb of java heap.

Storage requirement
...................

Elassandra nodes require persistent volumes to store Cassandra and Elasticsearch data.
You can use various kubernetes storage class including local and attached volumes.
Usage of SSD disks is recommended for better performances.

Network requirements
....................

The Elassandra Operator can deploy datacenters in 3 networking configuration.

In-cluster networking
_____________________

This is the default networking configuration where Cassandra and Elasticsearch pods listen on PODs private IP addresses.
In such configuration, Elassandra pods can only be reached by applications deployed in the same Kubernetes cluster through a headless service.

Out-of-cluster Networking with private IP addressing
____________________________________________________

In this configuration, Elassandra pods should be deployed with hostPort enabled to allow the inbound traffic
on Elassandra ports (Cassandra Native and Storage, Elasticsearch HTTP/HTTPS port) from the outside of the Kubernetes cluster.

This allows Elassandra pod to bind and broadcast Kubernetes node private IP address to interconnect datacenters through VPN or PVC.

Out-of-cluster Networking with Public IP addressing
___________________________________________________

In this configuration, Elassandra pods broadcast a public IP should be deployed with hostNetwork enabled, allowing Elassandra pods
to bind and broadcast public IP address of their Kubernetes nodes. In such configuration, cross datacenter connection
can rely on public IP adresses without the need of a VPN or a VPC.

