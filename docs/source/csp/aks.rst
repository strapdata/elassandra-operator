AKS
___

In order to create your Azure Kubernetes cluster, see the `Azure Quickstart <https://docs.microsoft.com/en-us/azure/aks/kubernetes-walkthrough>`_.

.. tip::

    To setup an AKS cluster with having a public IP address on each Kubernetes nodes, you need to install the following `Azure preview features
    <https://docs.microsoft.com/en-us/azure/aks/use-multiple-node-pools#assign-a-public-ip-per-node-for-your-node-pools-preview>`_.

    .. code::

        az extension add --name aks-preview
        az extension update --name aks-preview
        az feature register --name NodePublicIPPreview --namespace Microsoft.ContainerService

Create a resource group and regional AKS cluster with the Azure network plugin and a default nodepool based
on a `VirtualMachineScaleSets <https://docs.microsoft.com/en-us/rest/api/compute/virtualmachinescalesets>`_ that assigns
a public IP address to each virtual machine:

.. code::

    az group create -l ${AZURE_REGION} -n ${RESOURCE_GROUP_NAME}
    az aks create --name "${K8S_CLUSTER_NAME}" \
                  --resource-group ${RESOURCE_GROUP_NAME} \
                  --network-plugin azure \
                  --node-count 3 \
                  --node-vm-size Standard_D2_v3 \
                  --vm-set-type VirtualMachineScaleSets \
                  --output table \
                  --zone 1 2 3 \
                  --enable-node-public-ip
    az aks get-credentials --name "${K8S_CLUSTER_NAME}" --resource-group $RESOURCE_GROUP_NAME --output table

Unfortunately, AKS does not map VM's public IP address to the Kubernetes node external IP address, so the trick is to add these public IP addresses as a
kubernetes custom label ``elassandra.strapdata.com/public-ip`` to each nodes, here for the first Kubernetes node in our AKS cluster:

.. code::

    add_vmss_public_ip() {
       AKS_RG_NAME=$(az resource show --namespace Microsoft.ContainerService --resource-type managedClusters -g $RESOURCE_GROUP_NAME -n $K8S_CLUSTER_NAME | jq -r .properties.nodeResourceGroup)
       AKS_VMSS_INSTANCE=$(kubectl get nodes -o json | jq -r ".items[${1:-0}].metadata.name")
       PUBLIC_IP=$(az vmss list-instance-public-ips -g $AKS_RG_NAME -n ${AKS_VMSS_INSTANCE::-6} | jq -r ".[${1:-0}].ipAddress")
       kubectl label nodes --overwrite $AKS_VMSS_INSTANCE elassandra.strapdata.com/public-ip=$PUBLIC_IP
    }

    NODE_COUNT=$(kubectl get nodes --no-headers | wc -l)
    for i in $(seq 0 $((NODE_COUNT-1))); do
      add_vmss_public_ip $i
    done

As the result, you should have kubernetes nodes properly labeled with zone and public-ip:

.. code::

    kubectl get nodes -L failure-domain.beta.kubernetes.io/zone,elassandra.strapdata.com/public-ip
    NAME                                STATUS   ROLES   AGE   VERSION    ZONE           PUBLIC-IP
    aks-nodepool1-74300635-vmss000000   Ready    agent   54m   v1.15.11   westeurope-1   51.138.75.131
    aks-nodepool1-74300635-vmss000001   Ready    agent   54m   v1.15.11   westeurope-2   40.113.160.148
    aks-nodepool1-74300635-vmss000002   Ready    agent   54m   v1.15.11   westeurope-3   51.124.121.185

CoreDNS
.......

The default AKS coreDNS configuration does not resolve internet names, so the following configuration adds DNS forwarder to
our DNS zone and host aliases. Restart CoreDNS pods to reload our configuration.

.. code::

    HOST_ALIASES=$(kubectl get nodes -o custom-columns='INTERNAL-IP:.status.addresses[?(@.type=="InternalIP")].address,PUBLIC-IP:.metadata.labels.elassandra\.strapdata\.com/public-ip' --no-headers |\
        awk '{ gsub(/\./,"-",$2); printf("nodes.hosts[%d].name=%s,nodes.hosts[%d].value=%s,",NR-1, $2, NR-1, $1); }')
    kubectl delete configmap --namespace kube-system coredns-custom
    helm install --name coredns-custom --namespace kube-system \
        --set nodes.domain=internal.strapdata.com \
        --set $HOST_ALIASES \
      $HELM_REPO/coredns-custom
    kubectl delete pod --namespace kube-system -l k8s-app=kube-dns


AKS StorageClass
................

Azure persistent volumes are bound to an availability zone, so we need to defined one storageClass per zone in our Kubernetes cluster,
and each Elassandra rack or statefulSet will be bound to the corresponding storageClass.
This is done here using the HELM chart strapdata/storageclass.

.. code::

    for z in 1 2 3; do
        helm install --name ssd-$AZURE_REGION-$z --namespace kube-system \
            --set parameters.kind="Managed" \
            --set parameters.cachingmode="ReadOnly" \
            --set parameters.storageaccounttype="StandardSSD_LRS" \
            --set provisioner="kubernetes.io/azure-disk" \
            --set zone="$AZURE_REGION-${z}" \
            --set nameOverride="ssd-$AZURE_REGION-$z" \
            strapdata/storageclass
    done

AKS Firewall rules
..................

Finally, you may need to authorize inbound Elassandra connections on the following TCP ports:

* Cassandra storage port (usually 7000 or 7001) for internode connections
* Cassandra native CQL port (usually 9042) for client to node connections.
* Elasticsearch HTTP port (usually 9200) for the Elasticsearch REST API.

Assuming you deploy an Elassandra datacenter respectively using ports 39000, 39001, and 39002 exposed to the internet, with no source IP address restrictions:

.. code::

    AKS_RG_NAME=$(az resource show --namespace Microsoft.ContainerService --resource-type managedClusters -g $RESOURCE_GROUP_NAME -n "${K8S_CLUSTER_NAME}" | jq -r .properties.nodeResourceGroup)
    NSG_NAME=$(az network nsg list -g $AKS_RG_NAME | jq -r .[0].name)
    az network nsg rule create \
        --resource-group $AKS_RG_NAME \
        --nsg-name $NSG_NAME \
        --name elassandra_inbound \
        --description "Elassandra inbound rule" \
        --priority 2000 \
        --access Allow \
        --source-address-prefixes Internet \
        --protocol Tcp \
        --direction Inbound \
        --destination-address-prefixes '*' \
        --destination-port-ranges 39000-39002

Your Kubernetes cluster is now ready to deploy an Elassandra datacenter accessible from the internet world.