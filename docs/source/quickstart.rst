Quick Start
-----------

Once HELM 2 is deployed in your kubernetes cluster and you have added the strapdata HELM repository (see :ref:`helm-setup`),
deploy the Elassandra operator in the default namespace:

.. parsed-literal::

    helm install --namespace default --name elassandra-operator --wait strapdata/elassandra-operator

Deploy an Elassandra Datacenter in a dedicated namespace **ns1** with 6 nodes in 3 racks:

.. parsed-literal::

    helm install --namespace "ns1" --name "ns1-cl1-dc1" --set replicas=6 --wait strapdata/elassandra-datacenter

.. note:

    * To avoid mistakes, HELM release name MUST include the cluster name and datacenter name separated by a dash (and dash is not allowed in cluster and datacenter names).
    * The default storageclass is **standard**, but your can use any available storageclass.
    * Cassandra reaper, Elasticsearch and Kibana are enable by default.

Check Elassandra pods status, where pod names are **elassandra-[clusterName]-[dcName]-[rackIndex]-[podIndex]**:

.. code::

    kb get all -l app=elassandra
    NAME                         READY   STATUS    RESTARTS   AGE
    pod/elassandra-cl1-dc1-0-0   1/1     Running   0          8m29s
    pod/elassandra-cl1-dc1-0-1   1/1     Running   0          106s
    pod/elassandra-cl1-dc1-1-0   1/1     Running   0          7m16s
    pod/elassandra-cl1-dc1-1-1   1/1     Running   0          5m41s
    pod/elassandra-cl1-dc1-2-0   1/1     Running   0          6m28s
    pod/elassandra-cl1-dc1-2-1   1/1     Running   1          4m15s

    NAME                                       TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)                                            AGE
    service/elassandra-cl1-dc1                 ClusterIP   None            <none>        38001/TCP,39042/TCP,35001/TCP,9200/TCP,34001/TCP   8m32s
    service/elassandra-cl1-dc1-elasticsearch   ClusterIP   10.106.35.198   <none>        9200/TCP                                           8m32s
    service/elassandra-cl1-dc1-external        ClusterIP   10.102.118.49   <none>        39042/TCP,9200/TCP                                 8m32s

    NAME                                    READY   AGE
    statefulset.apps/elassandra-cl1-dc1-0   2/2     8m29s
    statefulset.apps/elassandra-cl1-dc1-1   2/2     7m16s
    statefulset.apps/elassandra-cl1-dc1-2   2/2     6m28s

Check the Elassandra DataCenter status:

.. code::

    kubectl get edc elassandra-cl1-dc1 -o yaml
    status:
      bootstrapped: true
      cqlStatus: NOT_STARTED
      health: GREEN
      keyspaceManagerStatus:
        keyspaces: []
        replicas: 0
      kibanaSpaceNames: []
      needCleanup: true
      needCleanupKeyspaces: []
      observedGeneration: 1
      operationHistory:
      - actions:
        - noop, wait for ready racks
        durationInMs: 82
        pendingInMs: 124
        submitDate: "2020-06-12T09:16:20.999Z"
        triggeredBy: status update statefulset=elassandra-cl1-dc1-0 replicas=1/2
      - actions:
        - noop, wait for ready racks
        durationInMs: 6
        pendingInMs: 62
        submitDate: "2020-06-12T09:16:20.952Z"
        triggeredBy: status update statefulset=elassandra-cl1-dc1-0 replicas=1/1
      - actions:
        - scale-up rack=c
        durationInMs: 158
        pendingInMs: 4
        submitDate: "2020-06-12T09:16:20.835Z"
        triggeredBy: status update statefulset=elassandra-cl1-dc1-2 replicas=2/2
      - actions:
        - noop, wait for ready racks
        durationInMs: 88
        pendingInMs: 110
        submitDate: "2020-06-12T09:13:51.264Z"
        triggeredBy: status update statefulset=elassandra-cl1-dc1-2 replicas=1/2
      - actions:
        - noop, wait for ready racks
        durationInMs: 6
        pendingInMs: 78
        submitDate: "2020-06-12T09:13:51.199Z"
        triggeredBy: status update statefulset=elassandra-cl1-dc1-2 replicas=1/1
      - actions:
        - scale-up rack=b
        durationInMs: 133
        pendingInMs: 1
        submitDate: "2020-06-12T09:13:51.065Z"
        triggeredBy: status update statefulset=elassandra-cl1-dc1-1 replicas=2/2
      - actions:
        - noop, wait for ready racks
        durationInMs: 8
        pendingInMs: 177
        submitDate: "2020-06-12T09:12:25.769Z"
        triggeredBy: status update statefulset=elassandra-cl1-dc1-1 replicas=1/2
      - actions:
        - noop, wait for ready racks
        durationInMs: 82
        pendingInMs: 15
        submitDate: "2020-06-12T09:12:25.748Z"
        triggeredBy: status update statefulset=elassandra-cl1-dc1-1 replicas=1/1
      - actions:
        - scale-up rack=a
        durationInMs: 223
        pendingInMs: 1
        submitDate: "2020-06-12T09:12:25.524Z"
        triggeredBy: status update statefulset=elassandra-cl1-dc1-2 replicas=1/1
      - actions:
        - noop, wait for ready racks
        durationInMs: 5
        pendingInMs: 199
        submitDate: "2020-06-12T09:11:38.802Z"
        triggeredBy: status update statefulset=elassandra-cl1-dc1-2 replicas=null/1
      - actions:
        - noop, wait for ready racks
        durationInMs: 75
        pendingInMs: 24
        submitDate: "2020-06-12T09:11:38.711Z"
        triggeredBy: status update statefulset=elassandra-cl1-dc1-2 replicas=null/0
      - actions:
        - scale-up rack index=2 name=b
        durationInMs: 275
        pendingInMs: 2
        submitDate: "2020-06-12T09:11:38.437Z"
        triggeredBy: status update statefulset=elassandra-cl1-dc1-1 replicas=1/1
      - actions:
        - noop, wait for ready racks
        durationInMs: 87
        pendingInMs: 268
        submitDate: "2020-06-12T09:10:50.816Z"
        triggeredBy: status update statefulset=elassandra-cl1-dc1-1 replicas=null/1
      - actions:
        - noop, wait for ready racks
        durationInMs: 105
        pendingInMs: 102
        submitDate: "2020-06-12T09:10:50.785Z"
        triggeredBy: status update statefulset=elassandra-cl1-dc1-1 replicas=null/0
      - actions:
        - scale-up rack index=1 name=a
        durationInMs: 403
        pendingInMs: 4
        submitDate: "2020-06-12T09:10:50.463Z"
        triggeredBy: status update statefulset=elassandra-cl1-dc1-0 replicas=1/1
      - actions:
        - noop, wait for ready racks
        durationInMs: 14
        pendingInMs: 201
        submitDate: "2020-06-12T09:09:37.737Z"
        triggeredBy: status update statefulset=elassandra-cl1-dc1-0 replicas=null/1
      phase: RUNNING
      rackStatuses:
        "0":
          desiredReplicas: 2
          fingerprint: 3e9c032-3feeca9
          health: GREEN
          index: 0
          name: c
          progressState: RUNNING
          readyReplicas: 2
        "1":
          desiredReplicas: 2
          fingerprint: 3e9c032-3feeca9
          health: GREEN
          index: 1
          name: a
          progressState: RUNNING
          readyReplicas: 2
        "2":
          desiredReplicas: 2
          fingerprint: 3e9c032-3feeca9
          health: GREEN
          index: 2
          name: b
          progressState: RUNNING
          readyReplicas: 2
      readyReplicas: 6
      reaperPhase: NONE
      remoteSeeders: []
      uuid: 59a965cf-b785-4ce3-a025-ba3cef51be36
      zones:
      - c
      - a
      - b

CQL connection to an Elassandra node (using the admin role):

.. code::

    kb exec -it -n ns1 elassandra-cl1-dc1-0-0 -- cqlsh
    Connected to cl1 at 127.0.0.1:39042.
    [cqlsh 5.0.1 | Cassandra 3.11.6.1 | CQL spec 3.4.4 | Native protocol v4]
    Use HELP for help.
    admin@cqlsh>

List Elassandra datacenter secrets:

.. code::

    kb get secret -n ns1
    NAME                             TYPE                                  DATA   AGE
    default-token-5tb48              kubernetes.io/service-account-token   3      30h
    elassandra-cl1                   Opaque                                6      11m
    elassandra-cl1-ca-key            Opaque                                1      30h
    elassandra-cl1-ca-pub            Opaque                                2      30h
    elassandra-cl1-dc1-keystore      Opaque                                2      11m
    elassandra-cl1-rc                Opaque                                3      11m
    elassandra-operator-truststore   Opaque                                3      30h
    ns1-cl1-dc1-token-bls59          kubernetes.io/service-account-token   3      11m

Connect to Kibana using port-forwarding:

.. code::

    kubectl port-forward pod/kibana 5601:5601

Alternatively, you can setup an ingress controller for the kibana instance.

Watch the Elassandra Datacenter CRD status until it is green, meaning all pods are up and running:

.. code::

    edctl watch-dc -n elassandra-cl1-dc1 -ns $NS --health GREEN
