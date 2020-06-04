Monitoring and Alerting
-----------------------

Elassandra operator monitoring
..............................

The Elassandra operator expose prometheus metrics on port 8081 by default, and the Operator HELM chart
adds the annotation ``prometheus.io/scrape=true` to enable automatic scraping by the prometheus operator.

The Elassandra opertor also expose the following mangement endpoints :

+----------+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Name     | Description                                                                                                                                                                                 |
+==========+=============================================================================================================================================================================================+
| /info    |  Returns static information about application build                                                                                                                                         |
+----------+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| /loggers | Returns information about available loggers and permits changing the configured log level (see `LoggersEndpoint<https://docs.micronaut.io/latest/guide/management.html#loggersEndpoint>`_)  |
+----------+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| /env     | Returns information about the environment and its property sources (see `EnvironmentEndpoint <https://docs.micronaut.io/latest/guide/management.html#environmentEndpoint>`_)                |
+----------+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| /caches  | Returns information about the caches and permits invalidating them (see `CachesEndpoint <https://docs.micronaut.io/latest/guide/management.html#cachesEndpoint>`_)                          |
+----------+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| /stop    | Shuts down the application server (see `ServerStopEndpoint <https://docs.micronaut.io/latest/guide/management.html#stopEndpoint>`_)                                                         |
+----------+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

Elassandra nodes monitoring
...........................

Elassandra nodes expose JVM, Cassandra and Elasticsearch metrics on port 9500 by default, and the Elassandra HELM chart
adds the annotation ``prometheus.io/scrape=true` to enable automatic scraping by the prometheus operator.


