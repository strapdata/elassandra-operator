Quick Start
===========

Once HELM 2 is deployed in your kubernetes cluster and you have added the strapdata HELM repository (see :ref:`helm-setup`),
deploy the Elassandra operator in the default namespace:

.. parsed-literal::

    helm install --namespace default --name elassandra-operator --wait strapdata/elassandra-operator

Deploy an Elassandra datacenter (Elasticsearch enabled) in a dedicated namespace **ns1** with 6 nodes (Cassandra racks depends on the zone label on your Kubernetes nodes):

.. parsed-literal::

    helm install --namespace "ns1" --name "ns1-cl1-dc1" --set replicas=6,elasticsearch.enabled=true,reaper.enabled=true --wait strapdata/elassandra-datacenter

.. note:

    * To avoid mistakes, HELM release name MUST include the cluster name and datacenter name separated by a dash (and dash is not allowed in cluster and datacenter names).
    * The default storageclass is **standard**, but your can use any available storageclass.
    * Cassandra reaper, Elasticsearch and Kibana are enable by default.

Check Elassandra pods status, where pod names are **elassandra-[clusterName]-[dcName]-[rackIndex]-[podIndex]**:

.. code::

    kubectl get all -n ns1
    NAME                                                   READY   STATUS    RESTARTS   AGE
    pod/elassandra-cl1-dc1-0-0                             1/1     Running   0          31m
    pod/elassandra-cl1-dc1-1-0                             1/1     Running   0          15m
    pod/elassandra-cl1-dc1-2-0                             1/1     Running   0          14m
    pod/elassandra-cl1-dc1-kibana-kibana-89dbb7988-rd25j   1/1     Running   0          30m
    pod/elassandra-cl1-dc1-reaper-bbbc795f6-c5b2h          1/1     Running   0          30m

    NAME                                       TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)                                             AGE
    service/elassandra-cl1-dc1                 ClusterIP   None            <none>        39000/TCP,39001/TCP,39004/TCP,39002/TCP,39006/TCP   31m
    service/elassandra-cl1-dc1-admin           ClusterIP   10.98.137.58    <none>        39004/TCP                                           31m
    service/elassandra-cl1-dc1-elasticsearch   ClusterIP   10.111.26.185   <none>        39002/TCP                                           31m
    service/elassandra-cl1-dc1-external        ClusterIP   10.105.8.20     <none>        39001/TCP,39002/TCP                                 31m
    service/elassandra-cl1-dc1-kibana-kibana   ClusterIP   10.99.128.39    <none>        5601/TCP                                            30m
    service/elassandra-cl1-dc1-reaper          ClusterIP   10.111.115.15   <none>        8080/TCP,8081/TCP                                   30m

    NAME                                               READY   UP-TO-DATE   AVAILABLE   AGE
    deployment.apps/elassandra-cl1-dc1-kibana-kibana   1/1     1            1           30m
    deployment.apps/elassandra-cl1-dc1-reaper          1/1     1            1           30m

    NAME                                                         DESIRED   CURRENT   READY   AGE
    replicaset.apps/elassandra-cl1-dc1-kibana-kibana-89dbb7988   1         1         1       30m
    replicaset.apps/elassandra-cl1-dc1-reaper-bbbc795f6          1         1         1       30m

    NAME                                    READY   AGE
    statefulset.apps/elassandra-cl1-dc1-0   1/1     31m
    statefulset.apps/elassandra-cl1-dc1-1   1/1     15m
    statefulset.apps/elassandra-cl1-dc1-2   1/1     14m

Check the Elassandra DataCenter status:

.. code::

    kubectl get edc elassandra-cl1-dc1 -n ns1 -o yaml | awk '/^status:/{flag=1}flag'
    status:
      bootstrapped: true
      cqlStatus: ESTABLISHED
      cqlStatusMessage: Connected to cluster=[cl1] with role=[elassandra_operator] secret=[elassandra-cl1/cassandra.elassandra_operator_password]
      health: GREEN
      keyspaceManagerStatus:
        keyspaces:
        - reaper_db
        - system_traces
        - elastic_admin
        - system_distributed
        - _kibana_1
        - system_auth
        replicas: 3
      kibanaSpaceNames:
      - kibana
      needCleanup: true
      needCleanupKeyspaces: []
      observedGeneration: 2
      operationHistory:
      - actions:
        - Update keyspace RF for [reaper_db]
        - Update keyspace RF for [system_traces]
        - Update keyspace RF for [system_distributed]
        - Update keyspace RF for [_kibana_1]
        - Update keyspace RF for [elastic_admin]
        - Update keyspace RF for [system_auth]
        - Updating kibana space=[kibana]
        durationInMs: 54874
        lastTransitionTime: "2020-06-29T15:16:11.279Z"
        pendingInMs: 3
        triggeredBy: Status update statefulset=elassandra-cl1-dc1-2 replicas=1/1
      - actions:
        - scale-up rack index=2 name=c
        durationInMs: 83
        lastTransitionTime: "2020-06-29T15:15:15.021Z"
        pendingInMs: 2
        triggeredBy: Status update statefulset=elassandra-cl1-dc1-1 replicas=1/1
      - actions:
        - scale-up rack index=1 name=b
        durationInMs: 209
        lastTransitionTime: "2020-06-29T15:14:23.834Z"
        pendingInMs: 6
        triggeredBy: Datacenter modified spec generation=2
      - actions:
        - Updating kibana space=[kibana]
        - Cassandra reaper registred
        durationInMs: 3717
        lastTransitionTime: "2020-06-29T15:00:15.586Z"
        pendingInMs: 8
        triggeredBy: Status update deployment=elassandra-cl1-dc1-reaper
      - actions:
        - Update keyspace RF for [reaper_db]
        - Update keyspace RF for [system_traces]
        - Update keyspace RF for [elastic_admin]
        - Update keyspace RF for [system_distributed]
        - Update keyspace RF for [_kibana_1]
        - Update keyspace RF for [system_auth]
        - Create or update role=[kibana]
        - Create or update role=[reaper]
        - Cassandra reaper deployed
        - Deploying kibana space=[kibana]
        durationInMs: 13938
        lastTransitionTime: "2020-06-29T14:59:36.781Z"
        pendingInMs: 7
        triggeredBy: Status update statefulset=elassandra-cl1-dc1-0 replicas=1/1
      - actions:
        - Datacenter resources deployed
        durationInMs: 4003
        lastTransitionTime: "2020-06-29T14:58:11.842Z"
        pendingInMs: 83
        triggeredBy: Datacenter added
      phase: RUNNING
      rackStatuses:
        "0":
          desiredReplicas: 1
          fingerprint: eec6512-3feeca9
          health: GREEN
          index: 0
          name: a
          progressState: RUNNING
          readyReplicas: 1
        "1":
          desiredReplicas: 1
          fingerprint: eec6512-3feeca9
          health: GREEN
          index: 1
          name: b
          progressState: RUNNING
          readyReplicas: 1
        "2":
          desiredReplicas: 1
          fingerprint: eec6512-3feeca9
          health: GREEN
          index: 2
          name: c
          progressState: RUNNING
          readyReplicas: 1
      readyReplicas: 3
      reaperRegistred: true
      zones:
      - a
      - b
      - c

CQL connection to an Elassandra node (using the admin role):

.. code::

    kb exec -it -n ns1 elassandra-cl1-dc1-0-0 -- cqlsh
    Connected to cl1 at 127.0.0.1:39042.
    [cqlsh 5.0.1 | Cassandra 3.11.6.1 | CQL spec 3.4.4 | Native protocol v4]
    Use HELP for help.
    admin@cqlsh>

Check Elasticsearch indices:

.. code::

    kubectl exec -it pod/elassandra-cl1-dc1-0-0 -n ns1 -- curl -k 'https://localhost:39002/_cat/indices?v'
    health status index     uuid                   pri rep docs.count docs.deleted store.size pri.store.size
    green  open   .kibana_1 yr48r36wRiWYqVPaRITgIw   1   0          0            0       230b           230b

List Elassandra datacenter secrets:

.. code::

    kubectl get secret -n ns1
    NAME                             TYPE                                  DATA   AGE
    default-token-zp5g6              kubernetes.io/service-account-token   3      53m
    elassandra-cl1                   Opaque                                6      53m
    elassandra-cl1-ca-key            Opaque                                1      53m
    elassandra-cl1-ca-pub            Opaque                                2      53m
    elassandra-cl1-dc1-keystore      Opaque                                2      10m
    elassandra-cl1-dc1-reaper        kubernetes.io/basic-auth              2      8m32s
    elassandra-cl1-kibana            Opaque                                1      43m
    elassandra-cl1-rc                Opaque                                3      10m
    elassandra-operator-truststore   Opaque                                3      53m
    kibana1-cl1-dc1-token-trk9d      kubernetes.io/service-account-token   3      10m

In order to connect to Kibana, get the kibana password in the Kubernetes secret **elassandra-cl1-kibana**:

.. code::

    kubectl get secret elassandra-cl1-kibana -n ns1 -o jsonpath='{.data.kibana\.kibana_password}' | base64 -D
    236fe20e-a155-4f2d-9bf5-a0002c471e59

Connect to Kibana using port-forwarding:

.. code::

    kubectl port-forward pod/kibana 5601:5601

Alternatively, you can setup an ingress controller for the kibana instance.

