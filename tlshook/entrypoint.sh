#!/bin/bash
# Generate the elassandra-operator certificate if needed, and patch the ValidatingWebhookConfiguration
# set -x

echo "NAMESPACE=$NAMESPACE"

TLS_CRT=/tls-secret/tls.crt
TLS_KEY=/tls-secret/tls.key
TLS_PKCS12=/tls/operator.p12

# TODO: check cert expiration date to renew
if [ -f $TLS_CRT ] && [ -s $TLS_CRT ] && [ `wc -l $TLS_CRT | awk '{print $1}'` -ge "2" ]; then
  echo "Use the existing TLS certificate in namespace=$NAMESPACE"
  openssl x509 -in $TLS_CRT -noout -text
  openssl pkcs12 -export -in $TLS_CRT -inkey $TLS_KEY -out $TLS_PKCS12 -passout pass:changeit
  CA_BUNDLE=$(cat $TLS_CRT | base64 | tr -d '\n')
else
  echo "Creating new TLS certificate for namespace=$NAMESPACE"
  openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
    -keyout /tmp/tls.key -out /tmp/tls.crt -extensions san -config \
    <(echo "[req]";
      echo distinguished_name=req;
      echo "[san]";
      echo subjectAltName=DNS:elassandra-operator.$NAMESPACE.svc,DNS:elassandra-operator.$NAMESPACE.svc.cluster.local,IP:127.0.0.1
      ) \
    -subj "/CN=localhost"
  openssl x509 -in /tmp/tls.crt -noout -text

  CA_BUNDLE=$(cat /tmp/tls.crt | base64 | tr -d '\n')
  CA_KEY=$(cat /tmp/tls.key | base64 | tr -d '\n')

  kubectl -n $NAMESPACE patch secret elassandra-operator -p="{\"type\": \"kubernetes.io/tls\", \"data\":{\"tls.crt\": \"$CA_BUNDLE\", \"tls.key\": \"$CA_KEY\" }}"
  openssl pkcs12 -export -in /tmp/tls.crt -inkey /tmp/tls.key -out $TLS_PKCS12 -passout pass:changeit
fi

# patch webhook caBundle
kubectl patch -n $NAMESPACE ValidatingWebhookConfiguration elassandra-operator-webhook --type='json' -p="[{\"op\": \"replace\", \"path\": \"/webhooks/0/clientConfig/caBundle\", \"value\":\"$CA_BUNDLE\"}]"
