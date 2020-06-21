Full Setup Example
==================

ExternalDNS
-----------

The ExternalDNS is used to automatically update your DNS zone. In the following setup, we will use
a DNS zone hosted on Azure, but you can use any other DNS provider supported by External DNS.

.. code::

    helm install $HELM_DEBUG --name my-externaldns --namespace default \
    --set logLevel="debug" \
    --set rbac.create=true \
    --set policy="sync",txtPrefix=$(kubectl config current-context)\
    --set sources[0]="service",sources[1]="ingress",sources[2]="crd" \
    --set crd.create=true,crd.apiversion="externaldns.k8s.io/v1alpha1",crd.kind="DNSEndpoint" \
    --set provider="azure" \
    --set azure.secretName="$AZURE_DNS_SECRET_NAME",azure.resourceGroup="$AZURE_DNS_RESOURCE_GROUP" \
    --set azure.tenantId="$AZURE_DNS_TENANT_ID",azure.subscriptionId="$AZURE_SUBSCRIPTION_ID" \
    --set azure.aadClientId="$AZURE_DNS_CLIENT_ID",azure.aadClientSecret="$AZURE_DNS_CLIENT_SECRET" \
    stable/external-dns

Key points:

* Watch for Kubernetes services, ingress, and the DNSEndpoint CRD published by the Elassandra operator when externalDns.enabled=true.
* With policy=sync, we need to setup a txtPrefix per Kubernetes cluster in order to avoid update conflict between
  clusters using the same DNS zone.

CoreDNS
-------

The Kubernetes CoreDNS is used for two reasons:

* Resolve DNS name of you DNZ zone from inside our Kubernetes cluster using DNS forwarders.
* Reverse resolution of the broadcast Elassandra public IP addresses to Kubernetes nodes names.

You can deploy the CodeDNS custom configuration with the coredns-forwarder HELM chart to basically replace the coredns-custom configmap,
and restart coreDNS pods.

.. code::

    kubectl delete configmap --namespace kube-system coredns-custom
    helm install $HELM_DEBUG --name coredns-forwarder --namespace kube-system \
        --set forwarders.domain="${DNS_DOMAIN}" \
        --set forwarders.hosts[0]="<forwarder1-ip-address>" \
        --set forwarders.hosts[1]="<forwarder1-ip-address>" \
        --set nodes.hosts[0].name="<node-0>",nodes.hosts[0].value="<node0-public-ip>" \
        strapdata/coredns-forwarder
    kubectl delete pod --namespace kube-system -l k8s-app=kube-dns

Traefik
-------

Deploy a Traefik ingress controller in order to access to web user interface of theo following components:

* Cassandra Reaper
* Kibana
* Prometheus Server
* Prometheus Alert Manager
* Grafana


.. code::

    helm install $HELM_DEBUG --name traefik --namespace kube-system \
    --set rbac.enabled=true,debug.enabled=true \
    --set dashboard.enabled=true,dashboard.domain=dashboard.${1:-$$TRAFIK_FQDN} \
    --set service.annotations."external-dns\.alpha\.kubernetes\.io/hostname"="*.${1:-$$TRAFIK_FQDN}" \
    stable/traefik

The externalDns annotation automatically publish the public IP of the traefik ingress controller in our DNS zone.
To avoid conflict between Kubernetes cluster using the same DNS zone, the TRAFIK_FQDN variable must
be the unique traefik FQDN in our DNS zone (example: traefik-dc1.my.domain.com)

.. warning::

    Of course, this traefik setup is not secure, an it's up to you to setup encryption and restrict access to those resources.

Prometheus operator
-------------------

To monitor your Kubernetes cluster, you can deploy the Prometheus operator, with the traefik ingress configured as shown bellow.

.. code::

    helm install $HELM_DEBUG --name my-promop \
    --set prometheus.ingress.enabled=true,prometheus.ingress.hosts[0]="prometheus.${TRAEFIK_FQDN}",prometheus.ingress.annotations."kubernetes\.io/ingress\.class"="traefik" \
    --set alertmanager.ingress.enabled=true,alertmanager.ingress.hosts[0]="alertmanager.${TRAEFIK_FQDN}",alertmanager.ingress.annotations."kubernetes\.io/ingress\.class"="traefik" \
    --set grafana.ingress.enabled=true,grafana.ingress.hosts[0]="grafana.${TRAEFIK_FQDN}",grafana.ingress.annotations."kubernetes\.io/ingress\.class"="traefik" \
    --set prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues=false \
    -f integ-test/prometheus-operator-values.yaml \
    stable/prometheus-operator

The file **integ-test/prometheus-operator-values** defines the following scarp config
to properly scrap pods having the prometheus annotation (The Elassandra operator does not deploy ServiceMonitor CRDs):

.. code::

    prometheus:
      prometheusSpec:
        additionalScrapeConfigs:
          - job_name: 'kubernetes-pods'
            kubernetes_sd_configs:
              - role: pod
            relabel_configs:
              - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
                action: keep
                regex: true
              - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
                action: replace
                target_label: __metrics_path__
                regex: (.+)
              - source_labels: [__address__, __meta_kubernetes_pod_annotation_prometheus_io_port]
                action: replace
                regex: ([^:]+)(?::\d+)?;(\d+)
                replacement: $1:$2
                target_label: __address__
              - action: labelmap
                regex: __meta_kubernetes_pod_label_(.+)
              - source_labels: [__meta_kubernetes_namespace]
                action: replace
                target_label: kubernetes_namespace
              - source_labels: [__meta_kubernetes_pod_name]
                action: replace
                target_label: kubernetes_pod_name
              - source_labels: [__meta_kubernetes_pod_name]
                action: replace
                target_label: instance

AKS Setup
---------

By default, the AKS does not allow to add public IP addresses on Kubernetes nodes.
The trick is to remove the kubernetes LoadBalancer, and create a new one with a Standard SKU.

.. code::

    AKS_RG_NAME=$(az resource show --namespace Microsoft.ContainerService --resource-type managedClusters -g $RESOURCE_GROUP_NAME -n $K8S_CLUSTER_NAME | jq -r .properties.nodeResourceGroup)
    az network lb delete --name kubernetes -g $AKS_RG_NAME
    az network lb create --name kubernetes -g $AKS_RG_NAME --sku Standard

Then, create and add a public IP to each Kubernetes nodes, and set the label kuberenetes.strapdata.com/public-ip with the node's public IP.

.. code::

    add_public_ip() {
       AKS_RG_NAME=$(az resource show --namespace Microsoft.ContainerService --resource-type managedClusters -g $RESOURCE_GROUP_NAME -n "${1}" | jq -r .properties.nodeResourceGroup)
       AKS_NODE=$(az vm list --resource-group $AKS_RG_NAME | jq -r ".[$2] .name")
       #az network nic ip-config list --nic-name "${AKS_NODE::-2}-nic-0" -g $AKS_RG_NAME

       # create a new public IP
       az network public-ip create -g $AKS_RG_NAME --name "${1}-ip$2" --dns-name "${1}-pub${2}" --sku Standard
       az network nic ip-config update -g $AKS_RG_NAME --nic-name "${AKS_NODE::-2}-nic-0" --name ipconfig1 --public-ip-address "${1}-ip$2"

       PUBLIC_IP=$(az network public-ip show -g $AKS_RG_NAME --name "${1}-ip$2" | jq -r ".ipAddress")
       kubectl label nodes --overwrite $AKS_NODE kubernetes.strapdata.com/public-ip=$PUBLIC_IP
    }
    add_public_ip ${1:-$K8S_CLUSTER_NAME} 0

As the result, you should have kubernetes nodes with the following labels:

.. code::

    kubectl get nodes -o wide -L failure-domain.beta.kubernetes.io/zone,kubernetes.strapdata.com/public-ip
    NAME                       STATUS   ROLES   AGE   VERSION    INTERNAL-IP   EXTERNAL-IP   OS-IMAGE             KERNEL-VERSION      CONTAINER-RUNTIME       ZONE   PUBLIC-IP
    aks-nodepool1-36354689-0   Ready    agent   26h   v1.15.11   10.240.0.4    <none>        Ubuntu 16.04.6 LTS   4.15.0-1083-azure   docker://3.0.10+azure   0      20.54.40.201

To connect two Elassandra datacenters running in distinct Kubernetes clusters, you now need to configure the CoreDNS to
resolve DNS names in your DNS zone and revers lookup public IP addresses of Kubernetes nodes to Kubernetes nodes name.
See the CoreDNS setup.

Finally, you may need to authorize inbound Elassandra connections on the following TCP ports:

* Cassandra storage port (usually 7000 or 7001) for internode connections
* Cassandra native CQL port (usually 9042) for client to node connections.
* Elasticsearch HTTP port (usually 9200) for the Elasticsearch REST API.

Assuming you deploy an Elassandra datacenter using ports 39000, 39001, and 39002 exposed to the internet, with no source IP address restrictions:

.. code::

    AKS_RG_NAME=$(az resource show --namespace Microsoft.ContainerService --resource-type managedClusters -g $RESOURCE_GROUP_NAME -n "${1}" | jq -r .properties.nodeResourceGroup)
    NSG_NAME=$(az network nsg list -g $AKS_RG_NAME | jq -r .[0].name)
    az network nsg rule create \
        --resource-group $AKS_RG_NAME \
        --nsg-name $NSG_NAME \
        --name elassandra_inbound \
        --description "Elassandra inbound rule" \
        --priority 2000 \
        --access Allow \
        --source-address-prefixes 0.0.0.0 \
        --protocol Tcp \
        --direction Inbound \
        --destination-address-prefixes '*' \
        --destination-port-ranges 39000 39001 39002

Your Kubernetes cluster is now ready to deploy an Elassandra datacenter accessible from the internet world.

GKE Setup
---------

CoreDNS installation
....................

GKE is provided with KubeDns by default, which does not allows to configure host aliases.
You should install CoreDNS and scale to 0 replica the KubeDNS as shown bellow:

.. code::

    wget -O - https://raw.githubusercontent.com/coredns/deployment/master/kubernetes/deploy.sh | bash | kubectl apply -f -
    kubectl scale deployment --replicas=0 kube-dns --namespace=kube-system
    kubectl scale deployment --replicas=0 kube-dns-autoscaler --namespace=kube-system


Webhook
.......

When Google configure the control plane for private clusters, they automatically configure VPC peering between your Kubernetes cluster’s network and a separate Google managed project.
In order to restrict what Google are able to access within your cluster, the firewall rules configured restrict access to your Kubernetes pods.
This means that in order to use the webhook component with a GKE private cluster, you must configure an additional firewall rule
to allow the GKE control plane access to your webhook pod.

You can read more information on how to add firewall rules for the GKE control plane nodes in the GKE docs

Alternatively, you can disable the hooks by setting webhookEnabled=false in your datacenter spec.


Elassandra datacenter
---------------------

On cluster1:

install_elassandra_datacenter default cl1 dc1 1 "networking.hostNetworkEnabled=true,networking.externalDns.enabled=true,networking.externalDns.domain=test.strapkube.com,networking.externalDns.root=cl1-dc1"

Deploy the first datacenter dc1, with the following settings:

On cluster2:

install_elassandra_datacenter default cl1 dc2 1 "networking.hostNetworkEnabled=true,networking.externalDns.enabled=true,networking.externalDns.domain=test.strapkube.com,networking.externalDns.root=cl1-dc2,cassandra.remoteSeeds[0]=cassandra-cl1-dc1-0-0.test.strapkube.com"
