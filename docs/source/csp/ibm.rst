IBM OpenShift
_____________

ibmcloud login -a https://cloud.ibm.com -u passcode -p sCeeIM6jz8
ibmcloud plugin install kubernetes-service
ibmcloud plugin install container-registry
ibmcloud oc init

ibmcloud oc cluster config -c $CLUSTER_ID --admin

.. code::

    kubectl get nodes -o wide -L failure-domain.beta.kubernetes.io/zone
    NAME             STATUS   ROLES           AGE    VERSION           INTERNAL-IP      EXTERNAL-IP       OS-IMAGE   KERNEL-VERSION                CONTAINER-RUNTIME                               ZONE
    10.123.231.196   Ready    master,worker   5d6h   v1.16.2+5e2117a   10.123.231.196   149.81.143.98     Red Hat    3.10.0-1127.10.1.el7.x86_64   cri-o://1.16.6-15.dev.rhaos4.3.gitebc053b.el7   fra05
    10.123.231.197   Ready    master,worker   5d6h   v1.16.2+5e2117a   10.123.231.197   149.81.143.103    Red Hat    3.10.0-1127.10.1.el7.x86_64   cri-o://1.16.6-15.dev.rhaos4.3.gitebc053b.el7   fra05
    10.194.114.115   Ready    master,worker   5d6h   v1.16.2+5e2117a   10.194.114.115   158.177.151.195   Red Hat    3.10.0-1127.10.1.el7.x86_64   cri-o://1.16.6-15.dev.rhaos4.3.gitebc053b.el7   fra02
    10.194.114.84    Ready    master,worker   5d6h   v1.16.2+5e2117a   10.194.114.84    158.177.151.200   Red Hat    3.10.0-1127.10.1.el7.x86_64   cri-o://1.16.6-15.dev.rhaos4.3.gitebc053b.el7   fra02
    10.75.141.118    Ready    master,worker   5d6h   v1.16.2+5e2117a   10.75.141.118    161.156.183.28    Red Hat    3.10.0-1127.10.1.el7.x86_64   cri-o://1.16.6-15.dev.rhaos4.3.gitebc053b.el7   fra04
    10.75.141.124    Ready    master,worker   5d6h   v1.16.2+5e2117a   10.75.141.124    161.156.183.20    Red Hat    3.10.0-1127.10.1.el7.x86_64   cri-o://1.16.6-15.dev.rhaos4.3.gitebc053b.el7   fra04

In order to allow Elassandra pods to access the host network and to tune the OS with the sysctl command, we need the following dedicated
`Security Context Constraints <https://docs.openshift.com/container-platform/4.1/authentication/managing-security-context-constraints.html>`_.

.. code::

    cat <<EOF | oc create -f -
    kind: SecurityContextConstraints
    apiVersion: v1
    metadata:
      name: scc-elassandra
    allowPrivilegeEscalation: true
    allowPrivilegedContainer: true
    allowHostNetwork: true
    allowHostPorts: true
    runAsUser:
      type: RunAsAny
    seLinuxContext:
      type: RunAsAny
    fsGroup:
      type: RunAsAny
    supplementalGroups:
      type: RunAsAny
    users:
    - my-admin-user
    groups:
    - my-admin-group
    volumes:
    - configMap
    - downwardAPI
    - emptyDir
    - persistentVolumeClaim
    - projected
    - secret
    EOF

Verify that the SCC was created:

.. code::

    oc get scc scc-elassandra
    NAME             AGE
    scc-elassandra   7s

To include access to SCCs for the elassandra role, specify the scc resource when creating a role in a dedicated namespace.

.. code::
    oc create namespace strapdata
    oc new-project strapdata
    oc create role strapdata-cl1-dc2-role --verb=use --resource=scc --resource-name=scc-elassandra -n strapdata

.. warning::

    You cannot assign a SCC to pods created in one of the default namespaces: **default**, **kube-system**, **kube-public**,
    **openshift-node**, **openshift-infra**, **openshift**. These namespaces should not be used for running pods or services.


oc adm policy add-scc-to-user privileged system:serviceaccount:strapdata:strapdata-cl1-dc1

Elassandra Operator CRD
-----------------------

OpenShift requires admin privilege to install a Custom Resource Definition that the Elassandra Operator has not.
Insatll the Elassandra datacenter and task CRDs.

.. code::

    oc apply -f java/model/src/main/resources/datacenter-crd.yaml
    oc apply -f java/model/src/main/resources/task-crd.yaml



Create a namespace to hold the strapdata resources (Elassandra, Kibana, Cassandra Reaper):

cat <<EOF | oc apply -n strapdata -f -
kind: ClusterRoleBinding
metadata:
  labels:
    app: elassandra-operator
    chart: elassandra-operator-0.3.0
    heritage: Tiller
    release: elassop
  name: elassop-elassandra-operator
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: elassop-elassandra-operator
subjects:
- kind: ServiceAccount
  name: elassop-elassandra-operator
  namespace: strapdata
EOF

IBM StorageClass
................

Persistent volumes are bound to an availability zone, so we need to defined one storageClass per zone in our Kubernetes cluster,
and each Elassandra rack or statefulSet will be bound to the corresponding storageClass.
This is done here using the HELM chart strapdata/storageclass.

.. code::

    ZONES=$(kb get nodes --no-headers -L failure-domain.beta.kubernetes.io/zone | awk '{ print $6 }' | sort | uniq | tr '\n' ' ')
    REGION=$(kb get nodes --no-headers -L failure-domain.beta.kubernetes.io/region | awk '{ print $6 }' | sort | uniq | tr '\n' ' ')
    for z in $ZONES ; do
        helm install --name ssd-$z --namespace $NAMESPACE \
            --set parameters.billingType="hourly" \
            --set-string parameters.classVersion="2" \
            --set-string parameters.iopsPerGB="4" \
            --set parameters.fsType="ext4" \
            --set parameters.sizeRange="[20-12000]Gi" \
            --set parameters.type="Endurance" \
            --set provisioner="ibm.io/ibmc-block" \
            --set region="$REGION" \
            --set nameOverride="ssd-$z" \
            $HELM_REPO/storageclass
    done








oc adm policy add-role-to-user elassop-elassandra-operator elassop-elassandra-operator -n strapdata
oc describe rolebinding.rbac -n strapdata elassop-elassandra-operator
oc get clusterrole elassop-elassandra-operator -o yaml
#ka
oc policy add-role-to-user elassop-elassandra-operator system:serviceaccount:strapdata:elassop-elassandra-operator
oc policy add-role-to-user elassop-elassandra-operator -z elassop-elassandra-operator
oc policy add-role-to-group view system:serviceaccounts:elassop-elassandra-operator -n strapdata
oc policy add-role-to-group edit system:serviceaccounts:elassop-elassandra-operator -n strapdata
oc adm policy add-cluster-role-to-user elassop-elassandra-operator elassop-elassandra-operator
oc adm policy add-cluster-role-to-group elassop-elassandra-operator elassop-elassandra-operator

oc policy add-role-to-user admin system:serviceaccount:strapdata:elassop-elassandra-operator

Install the Elassandra Operator in a dedicated namespace **strapdata**:

.. code::

    helm install --namespace strapdata --name elassop \
        --set env.OPERATOR_INSTALL_CRD=false,env.OPERATOR_WATCH_NAMESPACE=strapdata \
        --set env.OPERATOR_NODE_DNS_ZONE=test.strapkube.com \
        --wait $HELM_REPO/elassandra-operator

The Openshift DNS operator does not allows to add hosts aliases (like with the coreDNS host plugin),
so in order to resolve public IPs to internal Kuberenetes node addresses,
th Elassandra Operator is configured to publish DNSEndpoint CRDs in our DNS zone.


