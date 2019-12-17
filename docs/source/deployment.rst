Deployment
----------

The Elassandra operator can be deployed as a Kubernetes deployment, or using two HELM charts:

* A HELM chart to deploy the Elassandra Operator.
* A HELM chart to deploy an Elassandra datacenter CRD.

HELM Setup
..........

1. To install the HELM client, follow the `HELM setup instruction <https://helm.sh/docs/intro/install/>`_ for installing helm on your operating system.

2. Add the strapdata HELM repository as follow:

.. code::

    helm repo add strapdata https://chart.strapdata.com

3. Before installing the HELM tiller, create the tiller **serviceaccount** to enable RBAC support:

.. code::

    kubectl -n kube-system create serviceaccount tiller

Next, bind the tiller serviceaccount to the cluster-admin role:

.. code::

    kubectl create clusterrolebinding tiller --clusterrole cluster-admin --serviceaccount=kube-system:tiller

4. Then install the HELM tiller:

.. code::

    helm init --service-account tiller

Deploy the Elassandra Operator
..............................

Deploy the Elassandra operator pod as a Kubernetes Deployment. See `Operator Configuration <configuration.html#elassandra-operator>`_ for

.. code-block:: bash

    helm install --namespace default --name elop -f custom-values.yaml elassandra-operator

Check the Elassandra operator pod is up and running.

.. code-block:: bash

      kubectl --namespace default get pods -l "app=elassandra-operator,release=elop"

The Elassandra datacenter is deployed in the same namespace as the datacenter CRD. By default, the Elassandra operator watch for
new datacenter CRDs in the default Kubernetes namespace, but you can also register alternative namespaces, TODO....


Create CloudStorage secrets in order to receive the backup. (see `Backup & Restore <backup-restore.html>`_)
Here is an example for GCP cloud storage:

.. code-block:: bash

   kubectl create secret generic elassandra-mycluster-backup-gcp --from-file=/path/to/gcp.json --from-literal=project_id=your_gcp_project_id

Deploy an Elassandra Datacenter
...............................

Deploy an Elassandra Datacenter using a datacenter CRD. To uniquely identify each deployed datacenter in a kubernetes namespace,
the HELM release name MUST be in the form of [cluster_name]-[datacenter_name].

.. code-block:: bash

    helm install --namespace default --name cluster1-dc1 -f custom-values.yaml elassandra-datacenter

Depending on the number of Elassandra node replicas in the datacenter CRD, the Elassandra operator deploy a one StatefulSet
per Cassandra rack for each cloud provider zone. You can check Elassandra pods status using the label "app=elassandra" as shown below:

.. code-block:: bash

    kubectl --namespace default get pods -l "app=elassandra"

When an Elassandra node starts, several init containers runs before starting Elassandra:

* The **increase-vm-max-map-count** tune the linux system for Elassandra.
* The **nodeinfo** init container retrieve information form the underlying node, including the availability zone and public IP address if available.
* The **commitlogs** init container replay Cassandra commitlogs, flush and stop. Because replaying commitlogs may vary depending on commitlogs size
on disk, the Kubernetes liveness probe may timeout and lead to an endless restart loop.
