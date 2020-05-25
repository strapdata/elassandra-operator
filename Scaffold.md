# Scaffolf usage

# Init

kubectl config get-contexts

skaffold init
skaffold config set --kube-context kind-cluster1 local-cluster true
skaffold dev --default-repo localhost:5000
skaffold dev --kube-context kind-cluster1