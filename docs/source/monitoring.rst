Monitoring and Alerting
-----------------------


Prometheus operator
...................

Elassandra Enterprise nodes expose Cassandra and Elasticsearch a prometheus endpoint on <hostname>:9500/metrics.

The prometheus operator autmaticaly scrape PODs having the kubernetes annotation "prometheus.io/scrape=true".

