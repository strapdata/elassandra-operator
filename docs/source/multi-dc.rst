Mutli-datacenter deployment
===========================

With the Elassandra operator, you can connect Elassandra datacenter running in the same or distinct Kubernetes clusters.
The following chapter explains how to setup an Elassandra multi-datacenter deployment over the internet.

Kubernetes
----------

Here is instruction to prepare your Kubernetes cluster before deploying the Elassandra stack.

.. include:: csp/aks.rst
.. include:: csp/gke.rst
.. include:: csp/aws.rst
.. include:: csp/ibm.rst

Operators
---------

Elassandra Operator
___________________

Install the Elassandra operator in the default namespace:

.. code::

    helm install --namespace default --name elassop --wait $HELM_REPO/elassandra-operator

ExternalDNS
___________

The `ExternalDNS <https://github.com/kubernetes-sigs/external-dns>`_ is used to automatically update your DNS zone and
create an A record for the Cassandra broadcast IP addresses. You can use it with a public or a private DNS zone.

In the following setup, we will use a DNS zone hosted on Azure, but you can use any other DNS provider supported by External DNS.

.. code::

    helm install --name my-externaldns --namespace default \
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
* With ``policy=sync``, we need to setup a txtPrefix per Kubernetes cluster in order to avoid update conflict between
  clusters using the same DNS zone.

CoreDNS
_______

The Kubernetes CoreDNS is used for two reasons:

* Resolve DNS name of your private DNS zone from inside the Kubernetes cluster using DNS forwarders.
* Reverse resolution of the broadcast Elassandra public IP addresses to Kubernetes nodes private IP.

You can deploy the CodeDNS custom configuration with the strapdata coredns-forwarder HELM chart to basically install (or replace)
the coredns-custom configmap, and restart coreDNS pods.

Check the CoreDNS custom configuration:

.. code::

    kubectl get configmap -n kube-system coredns-custom -o yaml
    apiVersion: v1
    data:
      hosts.override: |
        hosts nodes.hosts internal.strapdata.com {
            10.132.0.57 146-148-117-125.internal.strapdata.com 146-148-117-125
            10.132.0.58 35-240-56-87.internal.strapdata.com 35-240-56-87
            10.132.0.56 34-76-40-251.internal.strapdata.com 34-76-40-251
            fallthrough
        }
    kind: ConfigMap
    metadata:
      creationTimestamp: "2020-06-26T16:45:52Z"
      name: coredns-custom
      namespace: kube-system
      resourceVersion: "6632"
      selfLink: /api/v1/namespaces/kube-system/configmaps/coredns-custom
      uid: dca59c7d-6503-48c1-864f-28ae46319725

Deploy a dnsutil pod:

.. code::

    cat <<EOF | kubectl apply -f -
    apiVersion: v1
    kind: Pod
    metadata:
      name: dnsutils
      namespace: default
    spec:
      containers:
      - name: dnsutils
        image: gcr.io/kubernetes-e2e-test-images/dnsutils:1.3
        command:
          - sleep
          - "3600"
        imagePullPolicy: IfNotPresent
      restartPolicy: Always
    EOF

Test resolution of public IP names to internal Kubernetes node IP address:

.. code::

    kubectl exec -ti dnsutils -- nslookup 146-148-117-125.internal.strapdata.com
    Server:		10.19.240.10
    Address:	10.19.240.10#53

    Name:	146-148-117-125.internal.strapdata.com
    Address: 10.132.0.57

.. _traefik-setup:

Traefik
_______

Deploy a Traefik ingress controller in order to access to web user interfaces for the following components:

* Cassandra Reaper
* Kibana
* Prometheus Server
* Prometheus Alert Manager
* Grafana

Here is simple Traefik deployment where TRAEFIK_FQDN=traefik-kube1.$DNS_DOMAIN:

.. code::

    helm install --name traefik --namespace kube-system \
        --set rbac.enabled=true \
        --set dashboard.enabled=true,dashboard.domain=dashboard.${TRAEFIK_FQDN} \
        --set service.annotations."external-dns\.alpha\.kubernetes\.io/hostname"="*.${TRAEFIK_FQDN}" \
        stable/traefik

The externalDns annotation automatically publish the public IP of the Traefik ingress controller in our DNS zone.
To avoid conflict between Kubernetes cluster using the same DNS zone, the TRAEFIK_FQDN variable must
be the unique traefik FQDN in our DNS zone (example: traefik-kube1.my.domain.com)

.. warning::

    Of course, this Traefik setup is not secure, an it's up to you to setup encryption and restrict access to those resources.


Multi-datacenter setup
----------------------

Deploy dc1 on kube1
___________________

Deploy the first datacenter **dc1** of the Elassandra cluster **cl1** in the Kubernetes cluster **kube1**,
with Kibana and Cassandra Reaper available through the Traefik ingress controller.

.. code::

    helm install --namespace $NAMESPACE --name "default-cl1-dc1" \
        --set dataVolumeClaim.storageClassName="ssd-{zone}" \
        --set cassandra.sslStoragePort="39000" \
        --set cassandra.nativePort="39001" \
        --set elasticsearch.httpPort="39002" \
        --set elasticsearch.transportPort="39003" \
        --set jvm.jmxPort="39004" \
        --set jvm.jdb="39005" \
        --set prometheus.port="39006" \
        --set replicas="3" \
        --set networking.hostNetworkEnabled=true \
        --set networking.externalDns.enabled=true \
        --set networking.externalDns.domain=${DNS_DOMAIN} \
        --set networking.externalDns.root=cl1-dc1 \
        --set kibana.enabled=true,kibana.spaces[0].ingressAnnotations."kubernetes\.io/ingress\.class"="traefik",kibana.spaces[0].ingressSuffix=kibana.${TRAEFIK_FQDN} \
        --set reaper.enabled=true,reaper.ingressAnnotations."kubernetes\.io/ingress\.class"="traefik",reaper.ingressHost=reaper.${TRAEFIK_FQDN} \
        --wait $HELM_REPO/elassandra-datacenter

Key points:

* The storageClass must exist in your Kubernetes cluster, default is the default storage class on Microsoft Azure.
* Because ``hostNetwork`` is enabled, you need to properly choose TCP ports to avoid conflict on the Kubernetes nodes.
* The env variable **TRAEFIK_FQDN** must be the public FQDN of your traefik deployment, traefik-kube1.$DNS_DOMAIN in our example.

Wait for the datacenter **dc1** to be ready:

.. code::

    edctl watch-dc --context kube1 -n elassandra-cl1-dc1 -ns default --health GREEN

Once your Elassandra datacenter is ready, check that you can reach the datacenter over the internet.
Get the Elassandra cluster root CA certificate and Cassandra admin password:

.. code::

    kubectl get secret elassandra-cl1-ca-pub --context kube1 -n default -o jsonpath='{.data.cacert\.pem}' | base64 -D > cl1-cacert.pem
    CASSANDRA_ADMIN_PASSWORD=$(kb get secret elassandra-cl1 --context kube1 -o jsonpath='{.data.cassandra\.admin_password}' | base64 -D)

Check your Elassandra datacenter:

.. code::

    SSL_CERTFILE=cl1-cacert.pem bin/cqlsh --ssl -u admin -p $CASSANDRA_ADMIN_PASSWORD cassandra-cl1-dc1-0-0.test.strapkube.com 39001
    Connected to cl1 at cassandra-cl1-dc1-0-0.test.strapkube.com:39001.
    [cqlsh 5.0.1 | Cassandra 3.11.6.1 | CQL spec 3.4.4 | Native protocol v4]
    Use HELP for help.
    admin@cqlsh>

Check the Elasticsearch cluster status:

.. code::

    curl -k --user admin:$CASSANDRA_ADMIN_PASSWORD "https://cassandra-cl1-dc1-0-0.test.strapkube.com:39002/_cluster/state?pretty"
    {
      "cluster_name" : "cl1",
      "cluster_uuid" : "8bbfeef1-6112-4509-0000-000000000000",
      "version" : 2925,
      "state_uuid" : "Pp36o9m9QU-AtYm8FepEHA",
      "master_node" : "8bbfeef1-6112-4509-0000-000000000000",
      "blocks" : { },
      "nodes" : {
        "8bbfeef1-6112-4509-0000-000000000000" : {
          "name" : "20.54.72.64",
          "status" : "ALIVE",
          "ephemeral_id" : "8bbfeef1-6112-4509-0000-000000000000",
          "transport_address" : "10.240.0.4:9300",
          "attributes" : {
            "rack" : "northeurope-1",
            "dc" : "dc1"
          }
        },
        "3a246ac2-1a0a-4f6e-0001-000000000000" : {
          "name" : "40.113.33.9",
          "status" : "ALIVE",
          "ephemeral_id" : "3a246ac2-1a0a-4f6e-0001-000000000000",
          "transport_address" : "10.240.0.35:9300",
          "attributes" : {
            "rack" : "northeurope-2",
            "dc" : "dc1"
          }
        },
        "ff8f0776-97cd-47a3-0002-000000000000" : {
          "name" : "20.54.80.104",
          "status" : "ALIVE",
          "ephemeral_id" : "ff8f0776-97cd-47a3-0002-000000000000",
          "transport_address" : "10.240.0.66:9300",
          "attributes" : {
            "rack" : "northeurope-3",
            "dc" : "dc1"
          }
        }
      },
      "metadata" : {
        "version" : 0,
        "cluster_uuid" : "8bbfeef1-6112-4509-0000-000000000000",
        "templates" : { },
        "indices" : { },
        "index-graveyard" : {
          "tombstones" : [ ]
        }
      },
      "routing_table" : {
        "indices" : { }
      },
      "routing_nodes" : {
        "unassigned" : [ ],
        "nodes" : {
          "8bbfeef1-6112-4509-0000-000000000000" : [ ]
        }
      },
      "snapshots" : {
        "snapshots" : [ ]
      },
      "restore" : {
        "snapshots" : [ ]
      },
      "snapshot_deletions" : {
        "snapshot_deletions" : [ ]
      }
    }

Once started, Kibana and Cassandra Reaper should be available in **kube1** at :

* http://kibana-kibana.traefik-kube1.$DNS_DOMAIN/
* http://reaper.traefik-kube1.$DNS_DOMAIN/webui

If the Prometheus Operator is deployed, you should get web user interfaces at:

* http://prometheus.traefik-kube1.$DNS_DOMAIN/
* http://alertmanager.traefik-kube1.$DNS_DOMAIN/
* http://grafana.traefik-kube1.$DNS_DOMAIN/login

For Kibana and Cassandra reaper, kibana and admin passwords are respectively stored in the Kubernetes secrets **elassandra-cl1-kibana** and **elassandra-cl1-dc1-reaper** in
the Elassandra datacenter namespace.

.. code::

    KIBANA_PASSWORD=$(kb get secret elassandra-cl1-kibana --context kube1 -o jsonpath='{.data.kibana\.kibana_password}' | base64 -D)
    REAPER_ADMIN_PASSWORD=$(kb get secret elassandra-cl1-dc1-reaper --context kube1 -o jsonpath='{.data.password}' | base64 -D)

Here is the Elasticsearch cluster state from the Kibana devtool:

.. image:: ./images/kibana-cluster-state.png

Here the Cassandra Reaper UI with our registered Cassandra cluster:

.. image:: ./images/reaper-cluster.png

Deploy dc2 on kube2
___________________

Once the Elassandra datacenter **dc1** is ready, you can deploy the datacenter **dc2** in the Kubernetes **kube2**.

First of all, copy the following Elassandra cluster secrets from the Kubernetes cluster **kube1** and
namespace **default**, into the Kubernetes cluster **kube2** namespace **default** (See the Security section for more information about these secrets):

* elassandra-cl1-dc1 (cluster passwords)
* elassandra-cl1-dc1-ca-pub (cluster root CA)
* elassandra-cl1-dc2-ca-key (cluster root CA key)
* elassandra-cl1-kibana (cluster kibana passwords)

.. code::

    for s in elassandra-cl1 elassandra-cl1-ca-pub elassandra-cl1-ca-key elassandra-cl1-kibana; do
        kubectl get secret $s --context $SRC_CONTEXT --export -n $SRC_NAMESPACE -o yaml | kubectl apply --context $CONTEXT -n $NAMESPACE -f -
    done

.. tip::

    These Elassandra cluster-wide secrets does not include any `ownerReference <https://kubernetes.io/docs/concepts/workloads/controllers/garbage-collection/>`_
    and won't be deleted when deleting the Elassandra datacenter because they could be used by another datacenter.
    So, it's up to you to properly delete these secrets when deleting an Elassandra cluster.

Deploy the datacenter **dc2** of the Elassandra cluster **cl1** in the Kubernetes cluster **cluster2**, with the following network settings:

.. code::

    helm install --namespace $NAMESPACE --name "$NAMESPACE-cl1-dc2" \
        --set dataVolumeClaim.storageClassName="ssd-{zone}" \
        --set cassandra.sslStoragePort=39000 \
        --set cassandra.nativePort=39001 \
        --set elasticsearch.httpPort=39002 \
        --set elasticsearch.transportPort=39003 \
        --set jvm.jmxPort=39004 \
        --set jvm.jdb=39005 \
        --set prometheus.port=39006 \
        --set replicas=3 \
        --set cassandra.remoteSeeds[0]=cassandra-cl1-dc1-0-0.${DNS_DOMAIN} \
        --set networking.hostNetworkEnabled=true \
        --set networking.externalDns.enabled=true \
        --set networking.externalDns.domain=${DNS_DOMAIN} \
        --set networking.externalDns.root=cl1-dc2 \
        --set kibana.enabled=true,kibana.spaces[0].ingressAnnotations."kubernetes\.io/ingress\.class"="traefik",kibana.spaces[0].ingressSuffix=kibana.${TRAEFIK_FQDN} \
        --set reaper.enabled=true,reaper.ingressAnnotations."kubernetes\.io/ingress\.class"="traefik",reaper.ingressHost=reaper.${TRAEFIK_FQDN} \
        --wait $HELM_REPO/elassandra-datacenter

Key points :

* Storage class must be defined in the Kubernetes cluster to match **ssd-{zone}**.
* The ``cassandra.remoteSeeds`` array must include the DNS name of a seed nodes in **dc1**.
* The ``networking.externalDns.root`` must be different from the **dc1** to avoid DNS name conflict, and you can include namespace or whatever in your naming plan.
* The **TRAEFIK_FQDN** env variable must point to the traefik public FQDN in the Kubernetes cluster **kube2**.

Wait for the datacenter **dc2** to be ready:

.. code::

    edctl watch-dc --context gke_strapkube1_europe-west1_kube2 -n elassandra-cl1-dc2 -ns default --replicas 3 --health GREEN
    19:29:20.254 [main] INFO  i.m.context.env.DefaultEnvironment.<init>:210 Established active environments: [cli]
    Waiting elassandra datacenter context=gke_strapkube1_europe-west1_kube2 name=elassandra-cl1-dc2 namespace=default health=GREEN timeout=600s
    19:29:21 ADDED: elassandra-cl1-dc2 phase=RUNNING heath=GREEN replicas=3 reaper=false cqlStatus=NOT_STARTED managedKeyspaces=[]
    done 143ms

The second datacenter has never bootstrapped, so nodes are started with auto_bootstrap=false.
Before streaming the Cassandra data, you now need to adjust the replication factor for the following keyspaces:

* system_distributed
* system_traces
* system_auth
* elastic_admin (if elasticsearch is enabled).
* any user keyspace that you want to replicate in **dc2**, *foo* in the provided example.

This is done with the following Elassandra task deployed on **dc1** (Kubernetes cluster **cluster1**):

.. code::

    cat <<EOF | kubectl apply --context kube1 -f -
    apiVersion: elassandra.strapdata.com/v1beta1
    kind: ElassandraTask
    metadata:
      name: replication-add-$$
      namespace: default
    spec:
      cluster: "cl1"
      datacenter: "dc1"
      replication:
        action: ADD
        dcName: "dc2"
        dcSize: 3
        replicationMap:
          reaper_db: 3
          foo: 1
    EOF
    edctl watch-task --context kube1 -n replication-add-573 -ns default --phase SUCCEED
    19:54:04.505 [main] INFO  i.m.context.env.DefaultEnvironment.<init>:210 Established active environments: [cli]
    Watching elassandra task context=kube1 name=replication-add-573 namespace=default phase=SUCCEED timeout=600s
    "19:54:06 ADDED: replication-add-573 phase=WAITING
    "19:55:02 MODIFIED: replication-add-573 phase=SUCCEED
    done 56772ms

Then on **dc2**, run a rebuild task to stream data from **dc1** and wait for termination:

.. code::

    cat <<EOF | kubectl apply --context gke_strapkube1_europe-west1_kube2 -f -
    apiVersion: elassandra.strapdata.com/v1beta1
    kind: ElassandraTask
    metadata:
      name: rebuild-dc2-$$
      namespace: default
    spec:
      cluster: "cl1"
      datacenter: "dc2"
      rebuild:
        srcDcName: "dc1"
    EOF
    edctl watch-task --context gke_strapkube1_europe-west1_kube2 -n rebuild-dc2-573 -ns default --phase SUCCEED
    19:59:29.458 [main] INFO  i.m.context.env.DefaultEnvironment.<init>:210 Established active environments: [cli]
    Watching elassandra task context=gke_strapkube1_europe-west1_kube2 name=rebuild-dc2-573 namespace=default phase=SUCCEED timeout=600s
    "19:59:30 ADDED: rebuild-dc2-573 phase=SUCCEED
    done 49ms

If Elasticsearch is enabled in **dc2**, you need to run restart Elassandra pods to update the Elasticsearch
cluster state since data have been populated by streaming data from **dc1**.

.. code::

    kubectl delete pod --namespace default -l app=elassandra,elassandra.strapdata.com/datacenter=dc2

Check the status of the Elassandra cluster running on AKS and GKE:

.. code::

    Datacenter: dc1
    ===============
    Status=Up/Down
    |/ State=Normal/Leaving/Joining/Moving
    --  Address          Load       Tokens       Owns    Host ID                               Rack
    UN  20.54.72.64      4.77 MiB   16           ?       8bbfeef1-6112-4509-0000-000000000000  northeurope-1
    UN  40.113.33.9      4.77 MiB   16           ?       3a246ac2-1a0a-4f6e-0001-000000000000  northeurope-2
    UN  20.54.80.104     4.75 MiB   16           ?       ff8f0776-97cd-47a3-0002-000000000000  northeurope-3
    Datacenter: dc2
    ===============
    Status=Up/Down
    |/ State=Normal/Leaving/Joining/Moving
    --  Address          Load       Tokens       Owns    Host ID                               Rack
    UN  34.76.40.251     3.65 MiB   16           ?       66d0eada-908a-407d-0000-000000000000  europe-west1-c
    UN  35.240.56.87     3.51 MiB   16           ?       6c578060-fc2b-4737-0002-000000000000  europe-west1-b
    UN  146.148.117.125  3.51 MiB   16           ?       84846161-944e-49e2-0001-000000000000  europe-west1-d

Finally, check the datacenter **dc2** is properly running on the Kubernetes cluster **kube2**:

.. code::

    SSL_CERTFILE=cl1-cacert.pem bin/cqlsh --ssl -u admin -p $CASSANDRA_ADMIN_PASSWORD cassandra-cl1-dc2-0-0.test.strapkube.com 39001
    Connected to cl1 at cassandra-cl2-dc1-0-0.test.strapkube.com:39001.
    [cqlsh 5.0.1 | Cassandra 3.11.6.1 | CQL spec 3.4.4 | Native protocol v4]
    Use HELP for help.
    admin@cqlsh>

.. code::

    curl -k --user admin:$CASSANDRA_ADMIN_PASSWORD "https://cassandra-cl1-dc2-0-0.test.strapkube.com:39002/_cluster/state?pretty"
    {
      "cluster_name" : "cl1",
      "cluster_uuid" : "8bbfeef1-6112-4509-0000-000000000000",
      "version" : 33,
      "state_uuid" : "0K-HIaLaR6qcQJNEbEF1lw",
      "master_node" : "66d0eada-908a-407d-0000-000000000000",
      "blocks" : { },
      "nodes" : {
        "84846161-944e-49e2-0001-000000000000" : {
          "name" : "146.148.117.125",
          "status" : "ALIVE",
          "ephemeral_id" : "84846161-944e-49e2-0001-000000000000",
          "transport_address" : "10.132.0.57:9300",
          "attributes" : {
            "rack" : "europe-west1-d",
            "dc" : "dc2"
          }
        },
        "66d0eada-908a-407d-0000-000000000000" : {
          "name" : "34.76.40.251",
          "status" : "ALIVE",
          "ephemeral_id" : "66d0eada-908a-407d-0000-000000000000",
          "transport_address" : "10.132.0.56:9300",
          "attributes" : {
            "rack" : "europe-west1-c",
            "dc" : "dc2"
          }
        },
        "6c578060-fc2b-4737-0002-000000000000" : {
          "name" : "35.240.56.87",
          "status" : "ALIVE",
          "ephemeral_id" : "6c578060-fc2b-4737-0002-000000000000",
          "transport_address" : "10.132.0.58:9300",
          "attributes" : {
            "rack" : "europe-west1-b",
            "dc" : "dc2"
          }
        }
      },
      "metadata" : {
        "version" : 5,
        "cluster_uuid" : "8bbfeef1-6112-4509-0000-000000000000",
        ...

Cassandra reaper now see the two datacenters:

.. image:: ./images/reaper-cluster-2dc.png

Cleaning up
-----------

Uninstall an Elassandra datacenter:

.. code::

    helm delete --purge elassandra-cl1-dc1

Uninstall the Elassandra operator and remove CRDs:

.. code::

    helm delete --purge elassandra-operator
    kubectl delete crd elassandradatacenters.elassandra.strapdata.com elassandratasks.elassandra.strapdata.com

