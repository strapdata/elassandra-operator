Security
--------

Kuberenetes RBAC
................

The elassandra operator runs with a dedicated Kubernetes serviceaccount ``strapkop-elassandra-operator`` and a
cluster role ``strapkop-elassandra-operator`` with the following restricted operations:

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
      name: strapkop-elassandra-operator
      resourceVersion: "5345657"
      selfLink: /apis/rbac.authorization.k8s.io/v1/clusterroles/strapkop-elassandra-operator
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
      - stable.strapdata.com
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

SSL/TLS Certificates
....................

The Elassandra operator can generate TLS keystores for Elassandra nodes
* On startup, the operator generates a self-signed root CA certificate stored in ca-pub and ca-key Kubernetes secrets if these does not exists.
* When a datacenter is deployed, a TLS keystore is generated from the root CA certificate if it does not exists in the secret
`èlassandra-[cluster]-[dc]-keystore``. This certificate has a wildcard certificate subjectAltName extension matching all Elassandra datacenter pods.
It also have the localhost and 127.0.0.1 extensions to allow local connections.

This TLS certificates and keys are used to secure:
* Cassandra node-to-node and client-to-node connections
* Cassandra JMX connection for administration and monitoring
* Elasticsearch client request overs HTTPS and Elasticsearch inter-node transport connections

When your cluster have multiple datacenters located in several Kubernetes clusters, these datacenter must share the same root CA
certificate secret. Thus, all nodes trust the same root CA.

Authentication and Access Control
.................................

Strapkop can automatically create Cassandra roles with a password defined as a Kubernetes secret, and set Cassandra permission and Elasticsearch privileges.