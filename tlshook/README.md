# TLS hook

Init container for the Elassandra Operator:
* Generate the operator TLS certificate (depends on the namespace)
* Update the ValidatingWebhookConfiguration caBundle