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

# Add zone label to nodes
zone:
	kubectl label nodes minikube failure-domain.beta.kubernetes.io/zone=local

# enable minikube docker registry localhost.localdomain:5000
# Add a k8s port-forward tunnel to the registry pod on port 5000 to push
setup:
	minikube addons enable registry

helm-init:
	kubectl apply -f helm/src/main/resources/rbac-tiller.yml
	helm init --history-max 200 --service-account tiller

dashboard:
	minikube dashboard

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

test-deploy-singlenode:
	helm install --namespace default --name teststrapkop -f helm/src/main/base-test/values-operator-localregistry.yml helm/src/main/helm/elassandra-operator
	echo "Wait operator pod ready"
	sleep 10
	helm install --namespace default --name singlenode-test -f helm/src/main/base-test/values-datacenter-singlenode-test.yml helm/src/main/helm/elassandra-datacenter
	echo "Wait elassandra pod ready"
	sleep 5
	kubectl wait --for=condition=ready --timeout=360s pod/elassandra-singlenode-test-local-0
	sleep 5
	# execute the test suite
	kubectl apply -f helm/src/test/singlenode/SingleNodeTestSuite.yaml

test-deploy-singlenode-jmxmp:
	helm install --namespace default --name teststrapkop -f helm/src/main/base-test/values-operator-localregistry.yml helm/src/main/helm/elassandra-operator
	echo "Wait operator pod ready"
	sleep 10
	helm install --namespace default --name singlenode-test -f helm/src/main/base-test/values-datacenter-singlenode-test.yml --set jmxmpEnabled=true --set jmxmpOverSSL=true helm/src/main/helm/elassandra-datacenter
	echo "Wait elassandra pod ready"
	sleep 5
	kubectl wait --for=condition=ready --timeout=360s pod/elassandra-singlenode-test-local-0
	sleep 5
	# execute the test suite
	kubectl apply -f helm/src/test/singlenode/SingleNodeTestSuite.yaml

test-cleanup:
	helm delete --purge singlenode-test
	helm delete --purge teststrapkop
	kubectl delete crd elassandradatacenters.stable.strapdata.com elassandratasks.stable.strapdata.com
	kubectl delete pvc data-volume-elassandra-singlenode-test-local-0
	kubectl delete deployment.apps/elassandra-singlenode-test-kibana-kibana deployment.apps/elassandra-singlenode-test-reaper

test-azure:
	helm install --namespace default --name teststrapkop -f helm/src/main/base-test/values-operator-azure.yml helm/src/main/helm/elassandra-operator
	echo "Wait operator pod ready"
	sleep 10
	kubectl wait --for=condition=ready --timeout=360s -l operator=elassandra po
	helm install --namespace default --name integazure-test -f helm/src/main/base-test/values-datacenter-integazure-test.yml helm/src/main/helm/elassandra-datacenter
	echo "Wait elassandra pod ready"
	sleep 5
	kubectl wait --for=condition=ready --timeout=360s -l app=elassandra po
	sleep 30
	kubectl wait --for=condition=ready --timeout=360s -l app=elassandra po
	# execute the test suite
	kubectl apply -f helm/src/test/threenodes/ThreeNodesTestSuite.yaml
