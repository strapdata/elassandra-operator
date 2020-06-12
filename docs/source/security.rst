Security
********

Kubernetes RBAC
===============

When Kubernetes ``hostNetwork`` or ``hostPort`` is enabled (see Networking), the Elassandra operator adds an init container
named **nodeinfo** allowing the Elassandra pods to get the node public IP address.

In order to access Kubernetes these nodes information, the Elassandra Operator HELM chart creates a dedicated ServiceAccount
suffixed by ``nodeinfo`` associated to the ClusterRole ``node-reader`` with the following permissions:

.. code::

    apiVersion: rbac.authorization.k8s.io/v1
    kind: ClusterRole
    metadata:
      labels:
        app: {{ template "elassandra-operator.name" . }}
        chart: {{ .Chart.Name }}-{{ .Chart.Version }}
        heritage: {{ .Release.Service }}
        release: {{ .Release.Name }}
      name: {{ template "elassandra-operator.fullname" . }}-node-reader
    rules:
      - apiGroups: [""]
        resources: ["nodes"]
        verbs: ["get", "list", "watch"]
      - apiGroups: [""]
        resources: ["pods"]
        verbs: ["get", "list", "watch"]


Certificate management
======================

Datacenter CA
-------------

In order to dynamically generates X509 certificates, the Elassandra-Operator use a root CA certificate and private key stored as
Kubernetes secrets. If theses CA secrets does not exist in the namespace where the datacenter is deployed, the operator automatically generates
a self-signed root CA certificate in that namespace:

* Secret **elassandra-{clusterName}-ca-pub** contains the root CA certificate as a PEM file and PKCS12 keystore.
* Secret **elassandra-{clusterName}-ca-key** contains the root CA private key in a PKCS12 keystore.

Of course, multi-datacenter Elassandra cluster runing in different namespaces or different kubernetes clusters should share the same root CA.
You can easily copy CA secrets from one namespace (NS) to another namespace (NS2) like this:

.. code ::

    kubectl get secret elassandra-cl1-ca-pub --namespace=$NS --export -o yaml | kubectl apply --namespace=$NS2 -f - || true
    kubectl get secret elassandra-cl1-ca-key --namespace=$NS --export -o yaml | kubectl apply --namespace=$NS2 -f - || true

To copy secrets from one kubernetes cluster to another one, you can probably use the
`Multicluster-Service-Account <https://github.com/admiraltyio/multicluster-service-account>`_ from `Admiralty <https://admiralty.io/>`_.

Generated Certificates
----------------------

When an Elassandra datacenter is deployed, a SSL/TLS keystore is generated from the namespaced root CA certificate if it does not exists in the secret
``elassandra-{clusterName}-{dcName}-keystore``. This certificate has a wildcard certificate subjectAltName extension matching all Elassandra datacenter pods.
It also have the localhost and 127.0.0.1 extensions to allow local connections.

This TLS certificates and keys are used to secure:

* Cassandra node-to-node and client-to-node connections.
* Cassandra JMX connection for administration and monitoring.
* Elasticsearch client request overs HTTPS and Elasticsearch inter-node transport connections.

Alternatively, your may use the `cert-manager <https://cert-manager.io/>`_ to manage theses certificates in a Kubernetes secret having the same name and content.

.. note::

    In a multi-datacenter cluster, you don't need to copy this secret to all datacenters.

Elassandra Credentials
======================

Elassandra operator automatically create strong passwords in the ``elassandra-{clusterName}`` secret for the following account if they does not yet exists :

* Cassandra **cassandra** superuser.
* Cassandra **admin** role with the cassandra superuser privilege.
* Cassandra **elassandra_operator** role with no superuser privilege.
* Cassandra JMX password
* Cassandra reaper admin password.

Here is such kubernetes secret for an Elassandra cluster named **cl1**:

.. code::

    kubectl get secret -n ns1 elassandra-cl1 -o yaml
    apiVersion: v1
    kind: Secret
    metadata:
      creationTimestamp: "2020-06-08T13:15:55Z"
      labels:
        app: elassandra
        app.kubernetes.io/managed-by: elassandra-operator
        elassandra.strapdata.com/cluster: cl1
        elassandra.strapdata.com/credential: "true"
      name: elassandra-cl1
      namespace: ns4
      ownerReferences:
      - apiVersion: elassandra.strapdata.com/v1
        blockOwnerDeletion: true
        controller: true
        kind: ElassandraDatacenter
        name: elassandra-cl1-dc1
        uid: 497b922d-874c-4f2d-bf05-a31a3f4682de
      resourceVersion: "131584"
      selfLink: /api/v1/namespaces/ns4/secrets/elassandra-cl1
      uid: 8b27562c-54c3-48fb-8bfd-91ab253da775
    type: Opaque
    data:
      cassandra.admin_password: MmY5N2IzYWUtNWYyYy00MGI0LTgwMmEtYjIzOTZlOGU2Yzhi
      cassandra.cassandra_password: MzM5MTVhOTYtNTQyMC00ZmI0LTlkMjctMDE2Y2VhNmNmZDM0
      cassandra.elassandra_operator_password: ZmQ2NTI5NGQtZTI1ZS00MzFhLWFkZTctYzE2ZjQwOGY5ZDU0
      cassandra.jmx_password: NzRmYzUxM2YtYjkzNi00NzU2LWE3ZTEtNmVjMGM4Y2NlMTc4
      cassandra.reaper_password: YWM0ZmM2ZGMtZWI5OC00ZWQxLWI1NTUtYjg1NjEyYTMwZGJl
      shared-secret.yaml: YWFhLnNoYXJlZF9zZWNyZXQ6IDE0MjY0YjI4LWQ0ZTAtNGFkMC05MDUzLWE0NjUwMzk2MDI3Mg==

Like for certificates, in a multi-datacenter Elassandra cluster deployed in different namespaces or different Kubernetes clusters,
you should copy this secret to all Elassandra datacenters.