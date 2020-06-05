Security
--------

Kuberenetes RBAC
________________

The elassandra operator runs with a dedicated Kubernetes serviceaccount ``elassandra-operator`` and a
cluster role ``elassandra-operator`` with the following restricted operations:

.. code::

    apiVersion: rbac.authorization.k8s.io/v1
    kind: ClusterRole
    metadata:
      creationTimestamp: "2019-10-17T22:55:19Z"
      labels:
        app: elassandra-operator
        chart: elassandra-operator-0.1.0
        heritage: Tiller
        release: strapkop
      name: elassandra-operator
      resourceVersion: "5345657"
      selfLink: /apis/rbac.authorization.k8s.io/v1/clusterroles/elassandra-operator
      uid: 311e5250-f131-11e9-a4ec-82615f3d8479
    rules:
    - apiGroups:
      - extensions
      resources:
      - thirdpartyresources
      verbs:
      - '*'
    - apiGroups:
      - apiextensions.k8s.io
      resources:
      - customresourcedefinitions
      verbs:
      - '*'
    - apiGroups:
      - elassandra.strapdata.com
      resources:
      - elassandradatacenter
      - elassandradatacenters
      - elassandradatacenter/status
      - elassandradatacenters/status
      - elassandratask
      - elassandratasks
      - elassandratask/status
      - elassandratasks/status
      verbs:
      - '*'
    - apiGroups:
      - apps
      resources:
      - statefulsets
      - deployments
      verbs:
      - '*'
    - apiGroups:
      - ""
      resources:
      - configmaps
      - secrets
      verbs:
      - '*'
    - apiGroups:
      - ""
      resources:
      - pods
      verbs:
      - list
      - delete
    - apiGroups:
      - ""
      resources:
      - services
      - endpoints
      - persistentvolumeclaims
      - persistentvolumes
      - ingresses
      verbs:
      - get
      - create
      - update
      - delete
      - list
    - nonResourceURLs:
      - /version
      - /version/*
      verbs:
      - get
    - apiGroups:
      - ""
      resources:
      - nodes
      verbs:
      - list
      - watch
    - apiGroups:
      - ""
      resources:
      - namespaces
      verbs:
      - list

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
______________________

In order to dynamically generates X509 certificates, the Elassandra-Operator use a root CA certificate and private key stored as
Kubernetes secrets. If theses CA secrets does not exist in the namespace where the datacenter is deployed, the operator automatically generates
a self-signed root CA certificate in that namespace:

* Secret **ca-pub** contains the root CA certificate as a PEM file and PKCS12 keystore. (respectively named *cacert.pem* and *truststore.p12*)
* Secret **ca-key** contains the root CA private key in a PKCS12 keystore. (named *ca.key*)

SSL/TLS Certificates
____________________

When an Elassandra datacenter is deployed, a SSL/TLS keystore is generated from the namespaced root CA certificate if it does not exists in the secret
``elassandra-[clusterName]-[dcName]-keystore``. This certificate has a wildcard certificate subjectAltName extension matching all Elassandra datacenter pods.
It also have the localhost and 127.0.0.1 extensions to allow local connections.

This TLS certificates and keys are used to secure:

* Cassandra node-to-node and client-to-node connections.
* Cassandra JMX connection for administration and monitoring.
* Elasticsearch client request overs HTTPS and Elasticsearch inter-node transport connections.

When your cluster have multiple datacenters located in several Kubernetes clusters, these datacenters must share
the same namespaced root CA certificate secret. Thus, all Elassandra cluster nodes trust the same root CA.

Authentication
______________

Elassandra operator can automatically setup a strong Cassandra password for the default Cassandra super user,
and create the following Cassandra roles.

* ``admin`` with the cassandra superuser privilege.
* ``elassandra_operator`` with no superuser privilege.

Passwords for these Cassandra roles comes form the folowing secret, created with random passwords if not yet existing when the datacenter is created.