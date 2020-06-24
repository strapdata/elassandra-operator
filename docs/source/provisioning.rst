Provision a Datacenter
**********************

Before deploying a datacenter, adjust your setting in the HELM values.yaml file:

.. code::

    replicas: 1
    parked: false

    image:
      elassandraRepository: strapdata/elassandra-node-dev
      tag: 6.8.4.5
      pullPolicy: Always
      pullSecrets: []

    # Elassandra node affinity STRICT or SLACK
    nodeAffinityPolicy: STRICT
    rbacEnabled: true
    serviceAccount:
    podTemplate:

    # Resource config
    resources:
      requests:
        cpu: 500m
        memory: 1Gi
      limits:
        cpu: 2000m
        memory: 2Gi

    # Storage config
    dataVolumeClaim:
      accessModes:
        - ReadWriteOnce
      storageClassName: standard
      resources:
        requests:
          storage: 500Mi
    # PVC delete policy, KEEP_PVC or DELETE_PVC
    decommissionPolicy: "DELETE_PVC"

    # Kubernetes Network config
    networking:
      hostPortEnabled: false
      hostNetworkEnabled: false
      nodeLoadBalancerEnabled: false

    # When hostNetwork or hostPort is enabled, and k8s node have a public IP address,
    # externalDns publish a DNS name for seed nodes allowing to be joined by remote DCs.
    externalDns:
      enabled: false
      root: "xxx"
      domain: "example.com"
      ttl: 300

    managedKeyspaces: {}
    #  - keyspace: gravitee
    #    rf: 3
    #    role: gravitee
    #    login: true
    #    superuser: false
    #    secretKey: gravitee
    #    grantStatements:
    #      - "GRANT gravitee TO gravitee"

    # JVM config
    jvm:
      computeJvmMemorySettings: true
      jdbPort: 4242
      # JMXMP is mandatory
      jmxPort: 7199
      jmxmpEnabled: true
      jmxmpOverSSL: true

    # Cassandra configuration
    cassandra:
      ssl: true
      authentication: CASSANDRA
      # Tell Cassandra to use the local IP address (INTERNAL_IP).
      snitchPreferLocal: true
      # Cassandra seeds config
      remoteSeeds: []
      remoteSeeders: []
      nativePort: 39042
      storagePort: 37000
      sslStoragePort: 37001

    # Cassandra reaper config
    reaper:
      image: strapdata/cassandra-reaper:2.1.0-SNAPSHOT-strapkop
      enabled: true
      jwtSecret: "68d45d8f-419f-429e-8ba0-7b475cba795d"
      podTemplate:
      ingressSuffix:
      ingressAnnotations: {}
      loggingLevel: "INFO"

    # Elasticsearch config
    elasticsearch:
      enabled: true
      httpPort: 9200
      transportPort: 9300
      ingressEnabled: false
      loadBalancerEnabled: false
      loadBalancerIp:
      config: {}
      datacenter:
        group:
        tags: []
      enterprise:
        enabled: true
        jmx: true
        https: true
        ssl: true
        aaa:
          enabled: true
          audit: true
        cbs: true
    kibana:
      enabled: true
      image: "docker.elastic.co/kibana/kibana-oss"
      spaces:
        - name: ""
          keyspaces: []
          ingressSuffix:
          ingressAnnotations: {}
          podTemplate:

    # Prometheus metrics exporter
    prometheus:
      enabled: true
      port: 9500

    # Override some config files in /etc/cassandra
    configs:
      logback.xml: |-
        <!--
        Licensed to the Apache Software Foundation (ASF) under one
        or more contributor license agreements.  See the NOTICE file
        distributed with this work for additional information
        regarding copyright ownership.  The ASF licenses this file
        to you under the Apache License, Version 2.0 (the
        "License"); you may not use this file except in compliance
        with the License.  You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

        Unless required by applicable law or agreed to in writing,
        software distributed under the License is distributed on an
        "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
        KIND, either express or implied.  See the License for the
        specific language governing permissions and limitations
        under the License.
        -->

        <configuration scan="true" debug="false">
        <jmxConfigurator />
        <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${CASSANDRA_LOGDIR}/system.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.FixedWindowRollingPolicy">
        <fileNamePattern>${CASSANDRA_LOGDIR}/system.log.%i.zip</fileNamePattern>
        <minIndex>1</minIndex>
        <maxIndex>20</maxIndex>
        </rollingPolicy>

        <triggeringPolicy class="ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy">
        <maxFileSize>500MB</maxFileSize>
        </triggeringPolicy>
        <encoder>
        <pattern>%date{ISO8601} %-5level [%thread] %F:%L %M %msg%n</pattern>
        </encoder>
        </appender>

        <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
        <pattern>%date{ISO8601} %-5level [%thread] %C.%M:%L %msg%n</pattern>
        </encoder>
        </appender>

        <appender name="AUDIT" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${CASSANDRA_LOGDIR}/audit.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.FixedWindowRollingPolicy">
        <fileNamePattern>${CASSANDRA_LOGDIR}/audit.log.%i.zip</fileNamePattern>
        <minIndex>1</minIndex>
        <maxIndex>20</maxIndex>
        </rollingPolicy>
        <triggeringPolicy class="ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy">
        <maxFileSize>500MB</maxFileSize>
        </triggeringPolicy>
        <encoder>
        <pattern>%date{ISO8601} %msg%n</pattern>
        </encoder>
        </appender>

        <logger name="com.thinkaurelius.thrift" level="ERROR"/>
        <logger name="org.apache" level="WARN" />

        <!-- Use env variables to customize logging level from docker -->
        <logger name="org.apache.cassandra" level="${LOGBACK_org_apache_cassandra:-WARN}" />
        <logger name="org.apache.cassandra.service.CassandraDaemon" level="${LOGBACK_org_apache_cassandra_service_CassandraDaemon:-INFO}" />
        <logger name="org.elassandra.shard" level="${LOGBACK_org_elassandra_shard:-INFO}" />
        <logger name="org.elassandra.indices" level="${LOGBACK_org_elassandra_indices:-INFO}" />
        <logger name="org.elassandra.index" level="${LOGBACK_org_elassandra_index:-WARN}" />
        <logger name="org.elassandra.discovery" level="${LOGBACK_org_elassandra_discovery:-WARN}" />
        <logger name="org.elasticsearch.cluster.service" level="${LOGBACK_org_elassandra_cluster_service:-DEBUG}" />
        <logger name="org.elasticsearch.cluster.metadata" level="DEBUG" />
        <logger name="org.elasticsearch" level="${LOGBACK_org_elasticsearch:-WARN}" />

        <root level="INFO">
          <appender-ref ref="STDOUT" />
        </root>
        <logger name="LogbackAuditor" level="DEBUG" additivity="false" >
           <appender-ref ref="AUDIT" />
        </logger>

        </configuration>

Once HELM is installed (see :ref:`helm-setup`), deploy an Elassandra Datacenter in a dedicated namespace **ns1** with 1 replica:

.. code::

    helm install --namespace "ns1" --name "ns1-cl1-dc1" -f values.yaml --wait strapdata/elassandra-datacenter

Peristent Storage
=================

Elassandra nodes require persistent volumes to store Cassandra and Elasticsearch data.
You can use various kubernetes storage class including local and attached volumes.
Usage of SSD disks is recommended for better performances.

To specify the persistence characteristics for each Elassandra node, you can describe a `PersistentVolumeClaimSpec <https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.12/#persistentvolumeclaimspec-v1-core>`_ as "dataVolumeClaim" value.

Persistent volume attached to availability zones
------------------------------------------------

The Elassandra operator deploys one Cassandra rack per availability zone to ensure data consistency when a zone is unavailable.
Each Cassandra rack is a Kubernetes StatefulSet, and rack names are Kubernetes node label ``failure-domain.beta.kubernetes.io/zone``.

In order to create Persistent Volume in the same availability zone as the StatefulSet,
you may create storage classes bound to availability zones of your cloud provider, as shown bellow using SSDs in GKE:

.. code::

    apiVersion: storage.k8s.io/v1
    kind: StorageClass
    metadata:
      name: ssd-b
      labels:
        addonmanager.kubernetes.io/mode: EnsureExists
        kubernetes.io/cluster-service: "true"
    provisioner: kubernetes.io/gce-pd
    parameters:
      type: pd-ssd
    allowVolumeExpansion: true
    reclaimPolicy: Delete
    volumeBindingMode: WaitForFirstConsumer
    allowedTopologies:
      - matchLabelExpressions:
          - key: failure-domain.beta.kubernetes.io/zone
            values:
              - europe-west1-b

In the Elassandra datacenter spec, you can then specify a ``storageClassName`` ìncluding a **{zone}** variable replaced
by the corresponding availability zone name.

.. code::

    dataVolumeClaim:
      accessModes:
        - ReadWriteOnce
      storageClassName: "ssd-{zone}"
      resources:
        requests:
          storage: 128Gi

Peristent volume decommission policy
------------------------------------

When deleting an Elassandra datacenter CRD, Elassandra PVCs are deleted.
If you want to keep PVCs, add the following in your datacenter spec.

.. code::

    decommissionPolicy: keep-pvc

.. tip::

    When scaling down the datacenter, PVC of removed Elassandra nodes are kept and you must delete these PVCs before scaling up,
    otherwise persistent volume are re-used.

Network Configuration
=====================

The Elassandra Operator can deploy datacenters in 3 networking configuration controlled by the following datacenter spec block:

.. code::

    networking:
      hostPortEnabled: false
      hostNetworkEnabled: false

In-cluster networking
---------------------

This is the default networking configuration where Cassandra and Elasticsearch pods listen on PODs private IP addresses.
In such configuration, Elassandra pods can only be reached by applications deployed in the same Kubernetes cluster through a headless service.

Out-of-cluster Networking with private IP addressing
----------------------------------------------------

In this configuration, Elassandra pods should be deployed with kubernetes ``hostPort`` enabled to allow the inbound traffic
on Elassandra ports (Cassandra Native and Storage, Elasticsearch HTTP/HTTPS port) from the outside of the Kubernetes cluster.

This allows Elassandra pod to bind and broadcast Kubernetes node private IP address to interconnect datacenters through VPN or PVC.

Out-of-cluster Networking with Public IP addressing
---------------------------------------------------

In this configuration, Elassandra pods broadcast a public IP should be deployed with ``hostNetwork`` enabled, allowing Elassandra pods
to bind and broadcast public IP address of their Kubernetes nodes. In such configuration, cross datacenter connection
can rely on public IP addresses without the need of a VPN or a VPC.

External DNS
------------

When Out-of-cluster networking is enabled, the **externalDns** section allows to automatically
publish DNS names for Elassandra nodes for remote CQL connections and multi-datacenter interconnections.
In this case, the **nodeinfo** init-container of Elassandra nodes
publishes a `DNSEndpoint <https://github.com/kubernetes-sigs/external-dns/blob/a7ac4f9b1e26edea01068dbcedbdd55b1f56165b/docs/contributing/crd-source/dnsendpoint-example.yaml>`_
CRD managed by the deployed `ExternalDNS operator <https://github.com/kubernetes-sigs/external-dns>`_.

.. jsonschema:: datacenter-spec.json#/properties/networking/properties/externalDns

See the `External DNS <https://github.com/helm/charts/tree/master/stable/external-dns>`_ to install and configure the ExternalDNS in your Kubernetes cluster.

Pod management
==============

Pod template
------------

You can customize Elassandra, Cassandra Reaper and Kibana pods by providing your
own `PodTemplate <https://kubernetes.io/docs/concepts/workloads/pods/pod-overview/#pod-templates>`_.
Thus, you can add labels, annotations, initContainers, environment variables, specify serviceAccount or priorityClassName, or customize resources.

For example, you can install custom Elasticsearch plugins before the elassandra container starts with
an `initContainer <https://kubernetes.io/docs/concepts/workloads/pods/init-containers/>`_, as shown bellow:

.. code::

    ...
    podTemplate:
      spec:
        initContainers:
        - name: install-plugins
          command:
          - sh
          - -c
          - |
            bin/elasticsearch-plugin install --batch plugin-xxx

Pod affinity
------------

You can define the `NodeAffinity <https://kubernetes.io/docs/concepts/configuration/assign-pod-node/#node-affinity>`_
for the Elassandra pods using the ``nodeAffinityPolicy`` attribute of the DatacenterSpec. Possible values are :

* STRICT : schedule elassandra pods only on nodes in the matching the ``failure-domain.beta.kubernetes.io/zone`` label (default value)
* SLACK  : schedule elassandra pods preferably on nodes in the matching the ``failure-domain.beta.kubernetes.io/zone`` label

Of course, when ``hostNetwork`` or ``hostPort`` is enabled (see Networking), using the SLACK affinity is not possible because all Elassandra nodes
of a cluster listen on the same TCP ports.

.. tip::

    In order to test the elassandra operator in a `kind cluster <https://kind.sigs.k8s.io/docs/user/quick-start/>`_,
    you just need to add ``failure-domain.beta.kubernetes.io/zone`` label to your kubernetes nodes, as shown in
    the following example with 3 workers:

    .. code::

        kubectl label nodes cluster1-worker failure-domain.beta.kubernetes.io/zone=a
        kubectl label nodes cluster1-worker2 failure-domain.beta.kubernetes.io/zone=b
        kubectl label nodes cluster1-worker3 failure-domain.beta.kubernetes.io/zone=c

    You can also temporarily disable a Kubernetes node:

    .. code::

        kubectl patch node cluster1-worker -p '{"spec":{"unschedulable":true}}'

Pod disruption budget
---------------------

For each datacenter, the Elassandra operator creates a `PodDisruptionBudget <https://kubernetes.io/docs/tasks/run-application/configure-pdb/>`_
to stop only one Elassandra pod at a time during a rolling restart. In some edge cases, you might update the ``maxPodUnavailable`` in your Elassandra
datacenter spec.

As an example, here is the deployed PodDisruptionBudget for a 3 nodes datacenter.

.. code::

    kubectl get pdb -o yaml
    apiVersion: v1
    kind: List
    metadata:
      resourceVersion: ""
      selfLink: ""
    items:
    - apiVersion: policy/v1beta1
      kind: PodDisruptionBudget
      metadata:
        annotations:
          elassandra.strapdata.com/datacenter-generation: "1"
        creationTimestamp: "2020-06-08T16:00:34Z"
        generation: 1
        labels:
          app: elassandra
          app.kubernetes.io/managed-by: elassandra-operator
          elassandra.strapdata.com/cluster: cl1
          elassandra.strapdata.com/datacenter: dc1
          elassandra.strapdata.com/parent: elassandra-cl1-dc1
        name: elassandra-cl1-dc1
        namespace: ns6
        ownerReferences:
        - apiVersion: elassandra.strapdata.com/v1beta1
          blockOwnerDeletion: true
          controller: true
          kind: ElassandraDatacenter
          name: elassandra-cl1-dc1
          uid: e8d88395-9e6f-4c1a-9a75-43bc4c44dae8
        resourceVersion: "15879"
        selfLink: /apis/policy/v1beta1/namespaces/ns6/poddisruptionbudgets/elassandra-cl1-dc1
        uid: 2b8bc2f7-64ca-4a0b-9fea-b7052fbf4480
      spec:
        maxUnavailable: 1
        selector:
          matchLabels:
            app: elassandra
            app.kubernetes.io/managed-by: elassandra-operator
            elassandra.strapdata.com/cluster: cl1
            elassandra.strapdata.com/datacenter: dc1
            elassandra.strapdata.com/parent: elassandra-cl1-dc1
      status:
        currentHealthy: 3
        desiredHealthy: 2
        disruptionsAllowed: 1
        expectedPods: 3
        observedGeneration: 1


Configuration
=============

JVM settings
------------

.. jsonschema:: datacenter-spec.json#/properties/jvm

Cassandra settings
------------------

.. jsonschema:: datacenter-spec.json#/properties/cassandra

Elasticsearch settings
----------------------

.. jsonschema:: datacenter-spec.json#/properties/elasticsearch

Elassandra configuration
------------------------

Elassandra configuration is generated by concatenating files from the following configuration sub-directories in /etc/cassandra:

* cassandra-env.sh.d
* cassandra.yaml.d
* elasticsearch.yml.d
* jvm.options.d

Files are loaded in alphanumeric order, so the last file overrides previous settings. You can customize
Elassandra configuration files by defining a Kubernetes configmap where each key is mapped to a file.
Here is an example to customize Cassandra settings from the cassandra.yaml file:

1. Create and deploy your user-config map:

.. code::

    apiVersion: v1
    kind: ConfigMap
    metadata:
      name: elassandra-cl1-dc1-user-config
      namespace: default
      labels:
        app: elassandra
        cluster: cl1
        datacenter: dc1
        parent: elassandra-cl1-dc1
    data:
      cassandra_yaml_d_user_config_overrides_yaml: |
        memtable_cleanup_threshold: 0.12

2. Patch the elassandraDatacenter CRD to map the user-config map to cassandra.yaml.d/009-user_config_overrides.yaml:

.. code::

    kubectl patch elassandradatacenter elassandra-cl1-dc1 --type merge --patch '{"spec":
        {"userConfigMapVolumeSource":
            {"name":"elassandra-cl1-dc1-user-config","items":[
                {"key":"cassandra_yaml_d_user_config_overrides_yaml","path":"cassandra.yaml.d/009-user_config_overrides.yaml"},
                {"key":"logback.xml","path":"logback.xml"}]
            }
        }
    }'

3. The Elassandra operator detects the CRD change and update per rack statefulsets.

.. CAUTION::

    If you patch the CRD with a wrong schema, the elassandra operator won't be able to parse and process it until you fix it.