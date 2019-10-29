# Minikube setup

## Setup

Enable minikube internal registry:

    minikube addons enable registry

Start minikube with insecure-registry:

    minikube start --cpus 4 --memory 4096 --insecure-registry "10.0.0.0/24"

## Use the minikube registry

Push to the minikube registry:

    docker push $(minikube ip):5000/test-img
    
## References

* https://minikube.sigs.k8s.io/docs/tasks/registry/insecure/
