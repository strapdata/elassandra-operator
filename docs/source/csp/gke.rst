GKE
___

Create a `Regional Kubernetes cluster <https://cloud.google.com/kubernetes-engine/docs/how-to/creating-a-regional-cluster>`_ on GCP:

.. code::

    gcloud container clusters create $K8S_CLUSTER_NAME \
      --region $GCLOUD_REGION \
      --project $GCLOUD_PROJECT \
      --machine-type "n1-standard-2" \
      --cluster-version=1.15 \
      --tags=$K8S_CLUSTER_NAME \
      --num-nodes "1"
    gcloud container clusters get-credentials $K8S_CLUSTER_NAME --region $GCLOUD_REGION --project $GCLOUD_PROJECT

Enable RBAC:

.. code::

    kubectl create clusterrolebinding cluster-admin-binding --clusterrole cluster-admin --user $(gcloud config get-value account)


CoreDNS installation
....................

GKE is provided with KubeDNS by default, which does not allows to configure host aliases required by our Kubernetes AddressTranslator.
So we need to install CoreDNS configured to import custom configuration (see `CoreDNS import plugin <https://coredns.io/plugins/import/>`_),
and configure KubeDNS with a stub domain to forward to CoreDNS.

.. code::

    helm install --name coredns --namespace=kube-system -f integ-test/gke/coredns-values.yaml stable/coredns

Where integ-test/gke/coredns-values.yaml is:

.. code::

    # Default values for coredns.
    # This is a YAML-formatted file.
    # Declare variables to be passed into your templates.

    image:
      repository: coredns/coredns
      tag: "1.6.9"
      pullPolicy: IfNotPresent

    replicaCount: 1

    resources:
      limits:
        cpu: 100m
        memory: 128Mi
      requests:
        cpu: 100m
        memory: 128Mi

    serviceType: "ClusterIP"

    prometheus:
      service:
        enabled: false
        annotations:
          prometheus.io/scrape: "true"
          prometheus.io/port: "9153"
      monitor:
        enabled: false
        additionalLabels: {}
        namespace: ""

    service:
      # clusterIP: ""
      # loadBalancerIP: ""
      # externalTrafficPolicy: ""
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "9153"

    serviceAccount:
      create: false
      # The name of the ServiceAccount to use
      # If not set and create is true, a name is generated using the fullname template
      name:

    rbac:
      # If true, create & use RBAC resources
      create: true
      # If true, create and use PodSecurityPolicy
      pspEnable: false
      # The name of the ServiceAccount to use.
      # If not set and create is true, a name is generated using the fullname template
      # name:

    # isClusterService specifies whether chart should be deployed as cluster-service or normal k8s app.
    isClusterService: true

    # Optional priority class to be used for the coredns pods. Used for autoscaler if autoscaler.priorityClassName not set.
    priorityClassName: ""

    # Default zone is what Kubernetes recommends:
    # https://kubernetes.io/docs/tasks/administer-cluster/dns-custom-nameservers/#coredns-configmap-options
    servers:
      - zones:
          - zone: .
        port: 53
        plugins:
          - name: errors
          # Serves a /health endpoint on :8080, required for livenessProbe
          - name: health
            configBlock: |-
              lameduck 5s
          # Serves a /ready endpoint on :8181, required for readinessProbe
          - name: ready
          # Required to query kubernetes API for data
          - name: kubernetes
            parameters: cluster.local in-addr.arpa ip6.arpa
            configBlock: |-
              pods insecure
              fallthrough in-addr.arpa ip6.arpa
              ttl 30
          # Serves a /metrics endpoint on :9153, required for serviceMonitor
          - name: prometheus
            parameters: 0.0.0.0:9153
          - name: forward
            parameters: . /etc/resolv.conf
          - name: cache
            parameters: 30
          - name: loop
          - name: reload
          - name: loadbalance
          - name: import
            parameters: "custom/*.override"

    # Complete example with all the options:
    # - zones:                 # the `zones` block can be left out entirely, defaults to "."
    #   - zone: hello.world.   # optional, defaults to "."
    #     scheme: tls://       # optional, defaults to "" (which equals "dns://" in CoreDNS)
    #   - zone: foo.bar.
    #     scheme: dns://
    #     use_tcp: true        # set this parameter to optionally expose the port on tcp as well as udp for the DNS protocol
    #                          # Note that this will not work if you are also exposing tls or grpc on the same server
    #   port: 12345            # optional, defaults to "" (which equals 53 in CoreDNS)
    #   plugins:               # the plugins to use for this server block
    #   - name: kubernetes     # name of plugin, if used multiple times ensure that the plugin supports it!
    #     parameters: foo bar  # list of parameters after the plugin
    #     configBlock: |-      # if the plugin supports extra block style config, supply it here
    #       hello world
    #       foo bar

    # expects input structure as per specification https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.11/#affinity-v1-core
    # for example:
    #   affinity:
    #     nodeAffinity:
    #      requiredDuringSchedulingIgnoredDuringExecution:
    #        nodeSelectorTerms:
    #        - matchExpressions:
    #          - key: foo.bar.com/role
    #            operator: In
    #            values:
    #            - master
    affinity: {}

    # Node labels for pod assignment
    # Ref: https://kubernetes.io/docs/user-guide/node-selection/
    nodeSelector: {}

    # expects input structure as per specification https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.11/#toleration-v1-core
    # for example:
    #   tolerations:
    #   - key: foo.bar.com/role
    #     operator: Equal
    #     value: master
    #     effect: NoSchedule
    tolerations: []

    # https://kubernetes.io/docs/tasks/run-application/configure-pdb/#specifying-a-poddisruptionbudget
    podDisruptionBudget: {}

    # configure custom zone files as per https://coredns.io/2017/05/08/custom-dns-entries-for-kubernetes/
    zoneFiles: []
    #  - filename: example.db
    #    domain: example.com
    #    contents: |
    #      example.com.   IN SOA sns.dns.icann.com. noc.dns.icann.com. 2015082541 7200 3600 1209600 3600
    #      example.com.   IN NS  b.iana-servers.net.
    #      example.com.   IN NS  a.iana-servers.net.
    #      example.com.   IN A   192.168.99.102
    #      *.example.com. IN A   192.168.99.102

    # optional array of extra volumes to create
    extraVolumes:
      - name: custom-config-volume
        configMap:
          name: coredns-custom
    # - name: some-volume-name
    #   emptyDir: {}
    # optional array of mount points for extraVolumes
    extraVolumeMounts:
      - name: custom-config-volume
        mountPath: /etc/coredns/custom
    # - name: some-volume-name
    #   mountPath: /etc/wherever

    # optional array of secrets to mount inside coredns container
    # possible usecase: need for secure connection with etcd backend
    extraSecrets: []
    # - name: etcd-client-certs
    #   mountPath: /etc/coredns/tls/etcd
    # - name: some-fancy-secret
    #   mountPath: /etc/wherever

    # Custom labels to apply to Deployment, Pod, Service, ServiceMonitor. Including autoscaler if enabled.
    customLabels: {}

    ## Configue a cluster-proportional-autoscaler for coredns
    # See https://github.com/kubernetes-incubator/cluster-proportional-autoscaler
    autoscaler:
      # Enabled the cluster-proportional-autoscaler
      enabled: false

      # Number of cores in the cluster per coredns replica
      coresPerReplica: 256
      # Number of nodes in the cluster per coredns replica
      nodesPerReplica: 16
      # Min size of replicaCount
      min: 0
      # Max size of replicaCount (default of 0 is no max)
      max: 0
      # Whether to include unschedulable nodes in the nodes/cores calculations - this requires version 1.8.0+ of the autoscaler
      includeUnschedulableNodes: false
      # If true does not allow single points of failure to form
      preventSinglePointFailure: true

      image:
        repository: k8s.gcr.io/cluster-proportional-autoscaler-amd64
        tag: "1.8.0"
        pullPolicy: IfNotPresent

      # Optional priority class to be used for the autoscaler pods. priorityClassName used if not set.
      priorityClassName: ""

      # expects input structure as per specification https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.11/#affinity-v1-core
      affinity: {}

      # Node labels for pod assignment
      # Ref: https://kubernetes.io/docs/user-guide/node-selection/
      nodeSelector: {}

      # expects input structure as per specification https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.11/#toleration-v1-core
      tolerations: []

      # resources for autoscaler pod
      resources:
        requests:
          cpu: "20m"
          memory: "10Mi"
        limits:
          cpu: "20m"
          memory: "10Mi"

      # Options for autoscaler configmap
      configmap:
        ## Annotations for the coredns-autoscaler configmap
        # i.e. strategy.spinnaker.io/versioned: "false" to ensure configmap isn't renamed
        annotations: {}

Once CoreDNS is installed, add a stub domain to forward request for domain **internal.strapdata.com**
to the CoreDNS service, and restart KubeDNS pods.
The **internal.strapdata.com** is just a dummy DNS domain used to resolve public IP addresses to Kubernetes nodes internal IP addresses.

.. code::

    COREDNS_SERVICE_IP=$(kubectl get  service -l k8s-app=coredns  -n kube-system -o jsonpath='{.items[0].spec.clusterIP}')
    KUBEDNS_STUB_DOMAINS="{\\\"internal.strapdata.com\\\": [\\\"$COREDNS_SERVICE_IP\\\"]}"
    kubectl patch configmap/kube-dns -n kube-system -p "{\"data\": {\"stubDomains\": \"$KUBEDNS_STUB_DOMAINS\"}}"
    kubectl delete pod -l k8s-app=coredns -n kube-system

Then configure coreDNS to add the following hosts aliases:

.. code::

    HOST_ALIASES=$(kubectl get nodes -o custom-columns='INTERNAL-IP:.status.addresses[?(@.type=="InternalIP")].address,EXTERNAL-IP:.status.addresses[?(@.type=="ExternalIP")].address' --no-headers |\
      awk '{ gsub(/\./,"-",$2); printf("nodes.hosts[%d].name=%s,nodes.hosts[%d].value=%s,",NR-1, $2, NR-1, $1); }')
    kubectl delete configmap --namespace kube-system coredns-custom
    helm install --name coredns-forwarder --namespace kube-system \
        --set nodes.domain=internal.strapdata.com \
        --set $HOST_ALIASES \
        strapdata/coredns-forwarder
    kubectl delete pod --namespace kube-system -l k8s-app=coredns

GKE StorageClass
................

Google cloud persistent volumes are bound to an availability zone, so we need to defined one storageClass per zone in our Kubernetes cluster,
and each Elassandra rack or statefulSet will be bound to the corresponding storageClass.
This is done here using the HELM chart strapdata/storageclass.

.. code::

    for z in europe-west1-b europe-west1-c europe-west1-d; do
        helm install --name ssd-$z --namespace kube-system \
            --set parameters.type="pd-ssd" \
            --set provisioner="kubernetes.io/gce-pd" \
            --set zone=$z,nameOverride=ssd-$z \
            strapdata/storageclass
    done

GKE Firewall rules
..................

Finally, you may need to authorize inbound Elassandra connections on the following TCP ports:

* Cassandra storage port (usually 7000 or 7001) for internode connections
* Cassandra native CQL port (usually 9042) for client to node connections.
* Elasticsearch HTTP port (usually 9200) for the Elasticsearch REST API.

Assuming you deploy an Elassandra datacenter respectively using ports 39000, 39001, and 39002 exposed to the internet, with no source IP address restrictions,
and Kubernetes nodes are properly tagged:

.. code::

    VPC_NETWORK=$(gcloud container clusters describe $K8S_CLUSTER_NAME --region $GCLOUD_REGION --format='value(network)')
    NODE_POOLS_TARGET_TAGS=$(gcloud container clusters describe $K8S_CLUSTER_NAME --region $GCLOUD_REGION --format='value[terminator=","](nodePools.config.tags)' --flatten='nodePools[].config.tags[]' | sed 's/,\{2,\}//g')
    gcloud compute firewall-rules create "allow-elassandra-inbound" \
      --allow tcp:39000-39002 \
      --network="$VPC_NETWORK" \
      --target-tags="$NODE_POOLS_TARGET_TAGS" \
      --description="Allow elassandra inbound" \
      --direction INGRESS

Webhook in GKE private cluster
..............................

When Google configure the control plane for **private clusters**, they automatically configure VPC peering between your
Kubernetes cluster’s network and a separate Google managed project. In order to restrict what Google are able to access within your cluster,
the firewall rules configured restrict access to your Kubernetes pods. This means that in order to use the webhook component
with a GKE private cluster, you must configure an additional firewall rule to allow the GKE control plane access to your webhook pod.

You can read more information on how to add firewall rules for the GKE control plane nodes in the GKE docs.
Alternatively, you can disable the hooks by setting webhookEnabled=false in your datacenter spec.

.. code::

    VPC_NETWORK=$(gcloud container clusters describe $K8S_CLUSTER_NAME --region $GCLOUD_REGION --format='value(network)')
    MASTER_IPV4_CIDR_BLOCK=$(gcloud container clusters describe $K8S_CLUSTER_NAME --region $GCLOUD_REGION --format='value(clusterIpv4Cidr)')
    NODE_POOLS_TARGET_TAGS=$(gcloud container clusters describe $K8S_CLUSTER_NAME --region $GCLOUD_REGION --format='value[terminator=","](nodePools.config.tags)' --flatten='nodePools[].config.tags[]' | sed 's/,\{2,\}//g')

    gcloud compute firewall-rules create "allow-apiserver-to-admission-webhook-443" \
      --allow tcp:8443 \
      --network="$VPC_NETWORK" \
      --source-ranges="$MASTER_IPV4_CIDR_BLOCK" \
      --target-tags="$NODE_POOLS_TARGET_TAGS" \
      --description="Allow apiserver access to admission webhook pod on port 443" \
      --direction INGRESS