Operations
----------

Elassandra Operator Deployment
..............................

The Elassandra operator can be beployed as a Kubernetes deployment, or useing the HELM chart **elassandra-opertor**.

In order to generate X509 certificates, the Elassandra operator requires a root CA certificate and private key stored as
Kubernetes secrets. If theses secrets does not exist when the operator is deployed, the operator automatically generates a self-signed
root CA certificate:

* Secret **ca-pub** contains the root CA certificate as a PEM file and PKCS12 keystore.
* Secret **ca-key** contains the root CA private key in a PKCS12 keystore.


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

To deploy an Elassandra datacenter you
When an Elassandra node starts, several init containers runs before starting Elassandra:

* The **increase-vm-max-map-count** tune the system for Elassandra.
* The **nodeinfo** init container retreive information form the underlying node, including zone and public IP address if available.
* The **commitlogs** init container replay Cassandra commitlogs, flush and stop. Because replaying commitlogs may vary depending
on commitlogs size on disk, the Kubernetes liveness probe may timeout and lead to an endless restart loop.


Get the node status
...................

Adjust Keyspace RF
..................

For managed keyspaces registred in Strapkop, the Cassandra Replication Factor can be automatically adjust according
to the desired number of replica and the number of available nodes.

Cassandra cleanup
.................

Enable/Disable search
.....................

Upgrade Elassandra
..................