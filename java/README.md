# Elassandra Kuberenetes Operator

## Design

ElassandraOperator basically run a perpetual watcher service (based on the k8s Watch API) to catch changes on any generic kubernetes resources.
When starting (or restarting), Elassandra operator gather a list of existing resources to initialize a map (a resource cache),
an emit events (added, changed, removed) in an rxJava subject when a change occurs according to the current cache.