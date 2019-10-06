Security
--------

Kuberenetes RBAC
................


SSL/TLS Certificates
....................

When deploying an Elassandra datacenter, Strapkop automaticaly generates a wilcard certificates for Elassandra nodes including required X509 extensions.
Theses SSL/TLS certificates and keys are used to secure:
* Cassandra node-to-node and client-to-node connections
* Cassandra JMX connection for administration and monitoring
* Elasticsearch client request overs HTTPS and internode connections

Authentication and Access Control
.................................

Strapkop can automatically create Cassandra roles with a password defined as a Kubernetes secret, and set Cassandra permission and Elasticsearch privileges.