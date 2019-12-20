# Elassandra Operator HELM

## Package HELM charts

```bash
./gradlew :helm:helmPackageElassandraDatacenterChart
./gradlew :helm:helmPackageElassandraOperatorChart
```

## Upload charts to azure blobstore

```bash
./gradlew :helm:uploadElassandraDatacenter
./gradlew :helm:uploadElassandraOperator
```

## Confure Azure Registry secret

Create a Service Principal to connect to the azure registry:

```bash
az ad sp create-for-rbac \
  --scopes /subscriptions/72738c1b-8ae6-4f23-8531-5796fe866f2e/resourcegroups/strapcloud.com/providers/Microsoft.ContainerRegistry/registries/strapdata \
  --role Contributor \
  --name strapreg
{
  "appId": "37dfee37-1c3d-4fed-8863-e35241acece7",
  "displayName": "strapreg",
  "name": "http://strapreg",
  "password": "4ccb75bc-6833-4cd4-88ad-3084d8f0d1af",
  "tenant": "566af820-2f8c-45ac-b975-647d2647b277"
}
```

Docker login with the SP and generated password:

```bash
docker login strapdata.azurecr.io -u <appId> -p <password>
```

Create a K8S secret with azure registry service principal:

```bash
kubectl create secret docker-registry azurecr \
  --docker-server strapdata.azurecr.io \
  --docker-email vroyer@strapdata.com \
  --docker-username=strapreg \
  --docker-password 4ccb75bc-6833-4cd4-88ad-3084d8f0d1af
```
## Publish for Strapkube

Upload HELM package on the Azure blogstore and in the Azure HELM repo.

    ./gradlew helm:uploadElassandraDatacenter
    ./gradlew helm:uploadElassandraOperator

## Deploy Elassandra Operator

    helm install --name kop --namespace default elassandra-operator

## Deploy a Datacenter

    helm install --name cl1-dc1 --namespace default elassandra-datacenter
    
## deploy Elassandra Operator with Minikube

### Prerequisites 

* install [minikube](https://kubernetes.io/docs/tasks/tools/install-minikube/) and create an instance with enough resources to run elassandra (the profile is optional)
```bash
# start minikube without internal registry 
minikube start --cpus 4 --memory 4096 --kubernetes-version v1.15.3 --profile strapdata-operator

# start minikube with internal registry (useful if you want to push locally build images)
minikube addons enable registry 
minikube start --cpus 4 --memory 4096 --kubernetes-version v1.15.3 --profile strapdata-operator --insecure-registry "10.0.0.0/24"
# to push an image to the minikube registry
# docker push $(minikube ip --profile strapdata-operator):5000/<my-image-name>
```

* add a label to the minikube node:
```bash
kubectl label nodes minikube failure-domain.beta.kubernetes.io/zone=local
```

* create the nodeinfo service account
```bash
cat << EOF > rbac-node-reader.yml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  namespace: default
  name: node-reader
rules:
- apiGroups: [""]
  resources: ["nodes"]
  verbs: ["get", "list", "watch"]
- apiGroups: [""]
  resources: ["pods"]
  verbs: ["get", "list", "watch"]
EOF

kubectl apply -f rbac-node-reader.yml 
kubectl create serviceaccount --namespace default nodeinfo
kubectl create clusterrolebinding nodeinfo-cluster-rule --clusterrole=node-reader --serviceaccount=default:nodeinfo
```

* install [helm](https://helm.sh/docs/using_helm/#installing-helm)
* create a [tiller account](https://helm.sh/docs/using_helm/#role-based-access-control)
```bash
cat << EOF > rbac.yml 
apiVersion: v1
kind: ServiceAccount
metadata:
  name: tiller
  namespace: kube-system
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: tiller
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: cluster-admin
subjects:
  - kind: ServiceAccount
    name: tiller
    namespace: kube-system
EOF

kubectl create -f rbac.yml
```
* initialize helm
```bash
helm init --history-max 200 --service-account tiller
```

### deploy Elassandra Operator

* create [docker credentials](https://kubernetes.io/docs/tasks/configure-pod-container/pull-image-private-registry/) as secret to download images from docker.repo.strapdata.com
```bash
kubectl create secret docker-registry strapdata-cr --docker-server=docker.repo.strapdata.com --docker-username=xxxx --docker-password="xxxx"
```

* adapt the chart values define into the helm project (path : src/main/minikube)
  * set the NODEINFO_SECRET to the one created by your ServiceAccount creation (here : nodeinfo-token-zhfc7)
```bash
kubectl get secret
NAME                   TYPE                                  DATA   AGE
default-token-b5pzc    kubernetes.io/service-account-token   3      4m9s
nodeinfo-token-zhfc7   kubernetes.io/service-account-token   3      10s
```
* install the Operator chart
```bash
strapkop$ ./gradlew helmInstallOperator 
```

* install the Datacenter chart
```bash
strapkop$ ./gradlew helmInstallDatacenter 
```

__NOTE__ : If you had deleted a chart before the installation, and if the helm install fails with the error `Error: UPGRADE FAILED: "elassandra-datacenter" has no deployed releases
` then execute `helm delete --purge elassandra-datacenter`.

If you want to delete the chart :
```bash
strapkop$ ./gradlew helmDeleteInstallDatacenter 
strapkop$ ./gradlew helmDeleteOperator 
```

## Install Microk8s on Ubuntu

* install microk8s using snap  (https://microk8s.io/docs/)

```bash
sudo snap install microk8s --classic --channel=1.15/stable
```

* add user to microk8s group 

```bash
sudo usermod -a -G microk8s <user>
```

* check install works properly

```bash
microk8s.status --wait-ready
microk8s.kubectl create deployment kubernetes-bootcamp --image=gcr.io/google-samples/kubernetes-bootcamp:v1
```

if the "bootcamp" img is running, create a kubeconfig file and export it to avoid using microk8s prefix on each command

```bash
microk8s.kubectl config view --raw > ~/.kube/microk8s.config 
export KUBECONFIG=~/.kube/microk8s.config
# create an alias kconf-microk8s to switch easily on this config
```

* Enable the DNS add-on

```bash
microk8s.enable dns
```

* Enable the Registry add-on

```bash
microk8s.enable registry
```

Add microk8s registry (localhost:32000) in docker config (/etc/docker/daemon.json)

* Install helm on microk8s cluster

```bash
kubectl apply -f helm/src/main/resources/rbac-tiller.yml
helm init --history-max 200 --service-account tiller
```

* Add the "zone" label on the node
 
```bash
kubectl label nodes $NODENAME failure-domain.beta.kubernetes.io/zone=local
```

* Authorize privileged container and change the insecure http port in kube-apiserver config 

Add **--allow-privileged** and **--insecure-port=16480** in the configuration file _/var/snap/microk8s/current/args/kube-apiserver_

Check there are no more references on 8080 in microk8s config files:
```bash
sudo sed -i s/:8080/:16480/ /var/snap/microk8s/current/args/cni-network /var/snap/microk8s/current/args/containerd /var/snap/microk8s/current/args/containerd-env /var/snap/microk8s/current/args/containerd-template.toml /var/snap/microk8s/current/args/containerd.toml /var/snap/microk8s/current/args/ctr /var/snap/microk8s/current/args/etcd /var/snap/microk8s/current/args/kube-apiserver /var/snap/microk8s/current/args/kube-controller-manager /var/snap/microk8s/current/args/kubectl /var/snap/microk8s/current/args/kubelet /var/snap/microk8s/current/args/kube-proxy /var/snap/microk8s/current/args/kube-scheduler
```

* Retsart microk8s 

```bash
microk8s.stop
microk8s.start
```