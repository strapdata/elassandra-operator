
![Elassandra Logo](docs/source/images/elassandra-operator.png)

# Elassandra Operator [![Build Status](https://travis-ci.com/strapdata/elassandra-operator.svg?token=PzEdBQpdXSgcm2zGdxUn&branch=master)](https://travis-ci.com/strapdata/elassandra-operator) [![Twitter](https://img.shields.io/twitter/follow/strapdataio?style=social)](https://twitter.com/strapdataio)

The Elassandra Kubernetes Operator automates the deployment and management of [Elassandra](https://github.com/strapdata/elassandra) 
datacenters in multiple Kubernetes clusters. By reducing the complexity of running a Cassandra or Elassandra cluster under Kubernetes,
it gives you the flexibility to migrate your data to any Kubernetes cluster with no downtime and the freedom to choose 
your cloud provider or run on-premise.

## Features

Elassandra Operator features:

* Manage one [Kubernetes StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/) per Cassandra rack to ensure data consistency across cloud-provider availability zones.
* Manage multiple Elassandra/Cassandra datacenters in the same or different Kubernetes clusters, in one or multiple namespaces.
* Manage rolling configuration changes, rolling upgrade/downgrade of Elassandra.
* Scale up/down Elassandra datacenters.
* Park/Unpark Elassandra datacenters (and associated Kibana and Cassandra Reaper instances).
* Implements Elassandra tasks to add/remove datacenters from an Elassandra cluster.
* Deploy [Cassandra Reaper](http://cassandra-reaper.io/) and register keyspaces to run continuous Cassandra repairs.
* Deploy multiple [Kibana](<https://www.elastic.co/fr/products/kibana>) instances with a dedicated Elasticsearch index in Elassandra.
* Expose Elassandra metrics for the [Prometheus Operator](https://prometheus.io/docs/prometheus/latest/querying/operators/).
* Publish DNS names allowing Elassandra nodes to be reachable from the internet or using dynamic private IP addresses.
* Automatically generates SSL/TLS certificates and strong passwords stored as Kubernetes secrets.
* Create Cassandra roles and automatically grants the desired permissions on Cassandra keyspaces.
* Automatically adjust the Cassandra Replication Factor for managed keyspaces, repair and cleanup after scale up/down.
* Provide a java [AddressTranslator](https://docs.datastax.com/en/developer/java-driver/3.6/manual/address_resolution/) for the Cassandra driver allowing to run applications in the same Kubernetes cluster as the Elassandra datacenter (similar to the [EC2MultiRegionAddressTranslator](https://docs.datastax.com/en/drivers/java/3.7/index.html?com/datastax/driver/core/policies/EC2MultiRegionAddressTranslator.html) but for any Kubernetes cluster).

## Status

Elassandra Operator version 0.1 is currently Alpha.

## Minimum Requirements

- Kubernetes cluster, 1.15 or newer.
- HELM 2

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
 * Community support available via [Elassandra Google Groups](https://groups.google.com/forum/#!forum/elassandra).
 * Post feature requests and bugs on https://github.com/strapdata/elassandra-operator/issues

## Contributing

The Elassandra-Operator is licensed under AGPL and you can contribute to bug reports,
documentation updates, tests, and features by submitting github issues or/and pull requests as usual.

### General design

The Elassandra Operator rely on the [Micronaut framework](https://micronaut.io/) and the 
(Kubernetes java client)[https://github.com/kubernetes-client/java] in a reactive programming style.
It does not require any Cassandra/Elassandra sidecar container, but requires a dedicated Elassandra docker image 
that warps the docker entrypoint for customization purposes.

### Developer setup

Requirements:
* Java 8
* Docker
* HELM 2
* Kind (for test)

Build:

```
./gradlew dockerPush dockerPushAllVersions -PregistryUrl="localhost:5000" -PregistryInsecure
./gradlew java:edctl:buildExec
```

Setup test cluster using kind:

```
integ-test/setup-cluster.sh
```

Run integration tests (see .travis.yml):

```
integ-test/test-admission.sh
integ-test/test-hostnetwork.sh
integ-test/test-reaper-registration.sh
integ-test/test-scaleup-park-unpark.sh
integ-test/test-multiple-dc-1ns.sh
integ-test/test-multiple-dc-2ns.sh
integ-test/test-rolling-upgrade.sh
```

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