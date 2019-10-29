MINIKUBE_IP=$(shell minikube ip)
.PHONY: init build deploy undeploy target deploy-kibana
#KB := /usr/local/Cellar/kubernetes-cli/1.11.1/bin/kubectl
KB := kubectl

all: push deploy

push:
	./gradlew clean dockerPush

# init minikube context
init:
	kubectl config use-context minikube

start:
	minikube start --cpus 4 --memory 4096 --insecure-registry "10.0.0.0/24"  --kubernetes-version v1.15.3 # --profile strapdata-operator

nodeinfo:
	kubectl label nodes minikube failure-domain.beta.kubernetes.io/zone=local
	kubectl apply -f helm/src/main/resources/rbac-nodeinfo.yml 
	kubectl create serviceaccount --namespace default nodeinfo
	kubectl create clusterrolebinding nodeinfo-cluster-rule --clusterrole=node-reader --serviceaccount=default:nodeinfo	

helm-init:
	kubectl apply -f helm/src/main/resources/rbac-tiller.yml
	helm init --history-max 200 --service-account tiller

dashboard:
	minikube dashboard

# enable minikube docker registry localhost.localdomain:5000
# Add a k8s port-forward tunnel to the registry pod on port 5000 to push
setup:
	minikube addons enable registry

deploy:
	./gradlew :helm:helmInstallStrapkop

update:
	./gradlew java:operator:jib
	$(KB) delete pod -l app=elassandra-operator

undeploy:
	helm delete --purge strapkop

deploy-dc1:
	helm install --namespace default --name cl1-dc1 -f helm/src/main/minikube/values-datacenter-cl1-dc1.yml helm/src/main/helm/elassandra-datacenter

undeploy-dc1:
	helm delete --purge cl1-dc1

