Operator setup
**************

Once HELM is properly deployed, deploy the Elassandra operator in the default namespace:

.. code::

    helm install --namespace default --name elassandra-operator --wait strapdata/elassandra-operator

After installation succeeds, you can get a status of your deployment:

.. code::

    helm status elassandra-operator

By default, the elassandra operator starts with a maximum java heap of 512m, and with the following resources limit:

.. code::

    resources:
      limits:
        cpu: 500m
        memory: 786Mi
      requests:
        cpu: 100m
        memory: 768Mi


You can adjust java settings by using the environment variable JAVA_TOOL_OPTIONS.

HELM chart configuration
========================

The following table lists the configurable parameters of the Elassandra operator chart and their default values.

+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| Parameter                  | Description                                                  | Default                                                   |
+============================+==============================================================+===========================================================+
| `image.repository`         | `elassandra-operator` image repository                       | `strapdata/elassandra-operator`                           |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `image.tag`                | `elassandra-operator` image tag                              | `6.8.4.5`                                                 |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `image.pullPolicy`         | Image pull policy                                            | `Always`                                                  |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `image.pullSecrets`        | Image pull secrets                                           | `nil`                                                     |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `replicas`                 | Number of `elassandra-operator` instance                     | `1`                                                       |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `watchNamespace`           | Namespace in which this operator should manage resources     | `nil` (watch all namespaces)                              |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `serverPort`               | HTTPS server port                                            | `443`                                                     |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `managementPort`           | Management port                                              | `8081`                                                    |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `jmxmpPort`                | JMXMP port                                                   | `7199`                                                    |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `prometheusEnabled`        | Enable prometheus metrics                                    | `true`                                                    |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `taskRetention`            | Elassandra task retention (Java duration)                    | `7D`                                                      |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `env`                      | Additional environment variables                             | `nil`                                                     |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `tls.key`                  | Operator TLS key (PEM base64 encoded)                        | See TLS Configuration                                     |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `tls.crt`                  | Operator TLS server certificate (PEM base64 encoded)         | See TLS Configuration                                     |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `tls.caBundle`             | Operator TLS CA bundle (PEM base64 encoded)                  | `tls.crt`                                                 |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `ingress.enabled`          | Enables ingress for /seeds endpoint                          | `false`                                                   |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `ingress.hosts`            | Ingress hosts                                                | `[]`                                                      |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `ingress.annotations`      | Ingress annotations                                          | `{}`                                                      |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `ingress.tls`              | Ingress TLS configuration                                    | `[]`                                                      |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `nodeSelector`             | Node labels for pod assignment                               | `{}`                                                      |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `rbacEnabled`              | Enable RBAC                                                  | `true`                                                    |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `webhook.enabled`          | Enable webhook validation                                    | `true`                                                    |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `webhook.failurePolicy`    | Webhook failure policy (**Fail** or **Ignore**)              | `Fail`                                                    |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `webhook.timeoutSeconds`   | Webhook validation timeout in seconds                        | `15`                                                      |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `tolerations`              | Toleration labels for pod assignment                         | `[]`                                                      |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+
| `affinity`                 | Affinity settings                                            | `{}`                                                      |
+----------------------------+--------------------------------------------------------------+-----------------------------------------------------------+

TLS Configuration
=================

The elassandra operator expose HTTPS endpoints for:

* Kubernetes readiness check (/ready)
* Kubernetes webhook validation (/validation)
* Expose Cassandra seeds IP addresses (/seeds) to Elassandra nodes and remote datacenters (see Cross Kubernetes Configuration).

The operator TLS server certificate defined by **tls.crt** and **tls.key** must include a subjectAltName matching the FQDN of the operator service name
(otherwise connection from elassandra nodes and the kubernetes API server to the operator will be rejected).

The provided default server certificate only meet this condition when the operator is deployed in the **default** namespace.
It was generated with the following command:

.. code::

    openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
      -keyout elassandra-operator.key -out elassandra-operator.crt -extensions san -config \
      <(echo "[req]";
        echo distinguished_name=req;
        echo "[san]";
        echo subjectAltName=DNS:elassandra-operator.default.svc,DNS:elassandra-operator.default.svc.cluster.local,IP:127.0.0.1
        ) \
      -subj "/CN=localhost"

If you want to deploy the elassandra operator in another namespace, you must generate a server certificate and key matching your namespace.

The **tls.caBundle** is used to configure the caBundle field of the [ValidatingWebhookConfiguration](see https://kubernetes.io/docs/reference/access-authn-authz/extensible-admission-controllers/).
and by default, it contains the server certificate.

The **tls.caBundle** is also used by elassandra nodes to trust connections to the remote Elassandra operators, in order to get the remote Cassandra seed IP addresses.
In such a case, the **tls.caBundle** must also include trust certificates of remote elassandra operators.

Operator authorizations
=======================

When RBAC is enabled, the elassandra operator runs with a dedicated Kubernetes serviceaccount ``elassandra-operator`` and a
cluster role ``elassandra-operator`` with the following authorizations in your Kubernetes cluster:

.. code::

    apiVersion: rbac.authorization.k8s.io/v1
    kind: ClusterRole
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
