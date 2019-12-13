Backup & Restore
----------------

The Elassandra operator supports taking backups a cluster managed by the operator and restoring those backups into a new cluster. This document outlines how to configure and manage backups.

Configuring backups for your cluster
------------------------------------

Depending on your environment and kubernetes distribution, these steps may be different.
The backup target location (where your backups will be stored) will determine how you configure your cluster.
Each supported cloud based backup location (Google Cloud Storage, AWS S3 and Azure Blobstore) uses the standard Java clients from those cloud providers
and those clients default credentials chains.

Credentials information must be provided through a kubernetes secret using the following naming convention *elassandra-[clusterName]-backup-[provider]* where

* *clusterName* must match the value in your Datacenter CRD
* *provider* must be one of the following values:
  * **gcp** : for a GCP blob storage
  * **aws** : for a AWS S3 bucket
  * **azure** : for an Azure blob storage

The entries of those secrets depend of the provider, see the following section for details.

.. note:: The secret should be defined before the first deployment of the Datacenter CRD.


Configuring AWS S3
...................

For talking to AWS S3, you have to provide the *access key*, the *secret key* and the *region* in the secret using this manifest
Here after there is an example of secret for the *clusterName* "cl1".

.. note:: Remember to change the name into the metadata section

.. code-block:: yaml

    apiVersion: v1
    kind: Secret
    metadata:
      name: elassandra-cl1-backup-aws
    type: Opaque
    stringData:
      region: __enter__
      access-key: __enter__
      secret-key: __enter__

Once you have update the manifest with your credentials, create the secret.

.. code-block:: bash

   kubectl create secret generic elassandra-cl1-backup-aws --from-file=./aws-secrets.yaml


Configuring GCP BLOB
....................

.. note:: The bucket has to manage ACL. (see `Access Control <https://cloud.google.com/storage/docs/access-control/lists>`_ )

For talking to GCP, you need a file created as a secret which will be mounted to container transparently and picked up by GCP initialisation mechanism.
The mount process will be managed by the Elassandra operator.

The file (a json file) must be named **gcp.json** and must contain the GCP service account information, you can find how to create this json following the `GCP documentation <https://cloud.google.com/iam/docs/creating-managing-service-account-keys>`_
In addition of the json file, you will have to register your GCP **project_id**.

Here after there is an example of secret for the *clusterName* "cl1".

.. code-block:: bash

   kubectl create secret generic elassandra-cl1-backup-gcp --from-file=/path/to/gcp.json --from-literal=project_id=your_gcp_project_id

Configuring AZURE BLOB
......................

For talking to AZURE BLOB, you have to provide the *Storage access name* and the *Storage access key* in the secret using this manifest
Here after there is an example of secret for the *clusterName* "cl1".

.. note:: Remember to change the name into the metadata section

.. code-block:: yaml

    apiVersion: v1
    kind: Secret
    metadata:
      name: elassandra-cl1-backup-azure
    type: Opaque
    stringData:
      storage-account: __enter__
      storage-key: __enter__

Once you have update the manifest with your credentials, create the secret.

.. code-block:: bash

   kubectl create secret generic elassandra-cl1-backup-azure --from-file=./azure-secrets.yaml

Backups your cluster
--------------------

The Elassandra Operator allows you to trigger a backup of your cluster by creating a backup task though the Task CRD.
Currently, the Elassandra operator manage a single backup mode by preserving the SSTable of the Elassandra nodes after a Snapshot.

To create a task, you have to provide:

* a backup name
* the cluster and data-center name
* the type of your cloud provider (AZURE_BLOB, GCP_BLOB, AWS_S3)
* the bucket name where the backup files will be uploaded

The backup name is set using the task name, this name will be used as *tag* for the SSTable snapshots.

Here is an example of Task manifest.

.. code-block:: yaml

    apiVersion: stable.strapdata.com/v1
    kind: ElassandraTask
    metadata:
      name: "backup001"
    spec:
      cluster: "cl1"
      datacenter: "dc1"
      backup:
        provider: AZURE_BLOB
        bucket: storage-bucket-name

Once the task applied, the Operator will send a backup request to each Sidecar container to perform a snaphost and then upload all relevant files on the specified cloud storage location.

.. note:: Take care to backup the kubernetes secrets containing the Cassandra credentials in order to avoid connection issue during the restore phase. For a cluster name 'cl1', secrets to backup is 'elassandra-cl1'

.. code-block:: bash

   kubectl get secrets elassandra-cl1 -o yaml > elassandra-cl1-credentials.yaml
   # store this file in a safe place to apply it before a restore

Restore your cluster
--------------------

Restore with the same cluster configuration
...........................................

Follow theses steps to restore an elassandra datacenter on a new Kubernetes cluster with the same number of nodes as the previous one.

.. note::
   The region used to create you cluster must be the same as the previous instance in order to match the node names.
   The number of nodes in each availability zone must be the same as the previous instance.

* Deploy the Elassandra Operator

.. code-block:: bash

   helm install --name myoperator -f operator-values.yaml elassandra-operator-0.2.0.tgz

* Apply the elassandra-cl1-credentials.yaml (see `Restore your cluster <#restore-your-cluster>`_) and check if the creation succeeds.
  This step is important in order to restore the Cassandra credentials at the operator level.
  If you miss this step, the operator will generate news secrets that will mismatch the ones preserved into the system_auth keyspace and restored from the cloud storage.

.. code-block:: bash

   kubectl apply -f elassandra-cl1-credentials.yaml
   kubectal get elassandra-cl1

* Apply the DataCenter CRD you want to restore with the 'restoreFromBackup' entry containing the name of the snapshot tag, the cloud provider and the bucket.

.. code-block:: bash

   # edit datacenter-values.yaml to add the restoration information
   # ex:
   cat << EOF >> datacenter-values.yaml

   restoreFromBackup:
     tag: "backup001"
     provider: "AZURE_BLOB"
     bucket: "storage-bucket-name"

   EOF
   helm install --name cl1-dc1 -f datacenter-values.yaml elassandra-datacenter-0.2.0.tgz

.. Restore with different cluster configuration
.. .............................................
.. TODO