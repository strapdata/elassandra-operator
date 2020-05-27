Security
--------

Kuberenetes RBAC
................

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

In order to access Kubernetes nodes information about server type, storage type and optional public IP address,
the Elassandra operator create a dedicated ServiceAccount suffixed by ``nodeinfo`` associated to the ClusterRole
``node-reader``. When starting Elassandra pods, this allows an init container to retrieve usefull information.

The node-reader has the following permissions:

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
......................

In order to generate X509 certificates, the Elassandra operator use a root CA certificate and private key stored as
Kubernetes secrets. If theses secrets does not exist when the operator is deployed, the operator automatically generates
a self-signed root CA certificate:

* Secret **ca-pub** contains the root CA certificate as a PEM file and PKCS12 keystore. (respectively named *cacert.pem* and *truststore.p12*)
* Secret **ca-key** contains the root CA private key in a PKCS12 keystore. (named *ca.key*)

SSL/TLS Certificates
....................

The Elassandra Operator can generate SSL/TLS keystores for Elassandra nodes:

* On startup, the operator generates a self-signed root CA certificate stored in ca-pub and ca-key Kubernetes secrets if these does not exists.
* When a datacenter is deployed, a SSL/TLS keystore is generated from the root CA certificate if it does not exists in the secret
``elassandra-[cluster]-[dc]-keystore``. This certificate has a wildcard certificate subjectAltName extension matching all Elassandra datacenter pods.
It also have the localhost and 127.0.0.1 extensions to allow local connections.

This TLS certificates and keys are used to secure:

* Cassandra node-to-node and client-to-node connections
* Cassandra JMX connection for administration and monitoring
* Elasticsearch client request overs HTTPS and Elasticsearch inter-node transport connections

When your cluster have multiple datacenters located in several Kubernetes clusters, these datacenter must share the same root CA
certificate secret. Thus, all nodes trust the same root CA.

Authentication
..............

Elassandra operator can automatically create Cassandra roles with a password defined as a Kubernetes secret, and set Cassandra permission and Elasticsearch privileges.