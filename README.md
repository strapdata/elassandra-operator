
![Elassandra Logo](docs/source/images/elassandra-operator.png)

# Elassandra Operator [![Build Status](https://travis-ci.com/strapdata/strapkop.svg?token=PzEdBQpdXSgcm2zGdxUn&branch=master)](https://travis-ci.com/strapdata/strapkop) [![Twitter](https://img.shields.io/twitter/follow/strapdataio?style=social)](https://twitter.com/strapdataio)

The Elassandra Kubernetes Operator automate the deployment and management of [Elassandra](https://github.com/strapdata/elassandra) datacenters 
in one or multiple Kubernetes clusters. 

## Features

Elassandra Operator features:

* Manage one [Kubernetes StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/) per Cassandra rack to ensure data consistency across cloud-provider availability zones.
* Manage mutliple Cassandra datacenters in the same or different Kubernetes clusters.
* Manage rolling configuration changes, rolling upgrade/downgrade of Elassandra, scale up/down Elassandra datacenters.
* Deploy [Cassandra Reaper](http://cassandra-reaper.io/) to run continuous Cassandra repairs.
* Deploy multiple [Kibana](<https://www.elastic.co/fr/products/kibana>) instances with a dedicated Elasticserach index in Elassandra.
* Park/Unpark Elassandra datacenters (and associated Kibana and Cassandra reaper instances).
* Expose Elassandra metrics for the [Prometheus Operator](https://prometheus.io/docs/prometheus/latest/querying/operators/).
* Publish public DNS names allowing Elassandra nodes to be reachable from the internet (Cassandra CQL and Elasticsearch REST API).
* Automatically generates SSL/TLS certificates and strong passwords stored as Kubernetes secrets.
* Create Cassandra roles and automatically grants the desired permissions on Cassandra keyspaces.
* Automatically adjust the Cassandra Replication Factor for managed keyspaces, repair and cleanup after scale up/down.

## Documentation

Please see the [Elassandra-Operator documentation](https://operator.elassandra.io)

## Build from source

Publish the docker images (operator + elassandra + cassandra reaper):
```bash
./gradlew dockerPush -PregistryUsername=$DOCKER_USERNAME -PregistryPassword=$DOCKER_PASSWORD -PregistryUrl=$DOCKER_URL
```

Publish in local insecure registry
```bash
./gradlew dockerPush -PregistryUrl="localhost:5000" -PregistryInsecure
```

Build parameters are located in `gradle.properties`.

## Support

 * Commercial support is available through [Strapdata](http://www.strapdata.com/).
 * Community support available via [elassandra google groups](https://groups.google.com/forum/#!forum/elassandra).
 * Post feature requests and bugs on https://github.com/strapdata/elassandra-operator/issues
 
## License

Copyright (C) 2020 Strapdata SAS (support@strapdata.com)

The Elassandra-Operator is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

The Elassandra-Operator is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.

## Acknowledgments

* Elasticsearch, Logstash, Beats and Kibana are trademarks of Elasticsearch BV, registered in the U.S. and in other countries.
* Apache Cassandra, Apache Lucene, Apache, Lucene and Cassandra are trademarks of the Apache Software Foundation.
* Elassandra is a trademark of Strapdata SAS.