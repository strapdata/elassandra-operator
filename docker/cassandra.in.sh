. /usr/share/cassandra/default-cassandra.in.sh

JVM_OPTS=${JVM_OPTS:=}

JVM_OPTS="${JVM_OPTS} ${JAVA_AGENT}"

# elasticsearch requires -Dcassandra.storagedir to start properly
JVM_OPTS="${JVM_OPTS} -Dcassandra.storagedir=/var/lib/cassandra"

JVM_OPTS="${JVM_OPTS} -Dcassandra.config.loader=com.strapdata.cassandra.k8s.ConcatenatedYamlConfigurationLoader"
JVM_OPTS="${JVM_OPTS} -Dcassandra.config=/usr/share/cassandra/cassandra.yaml:/etc/cassandra/cassandra.yaml:/etc/cassandra/cassandra.yaml.d"

JVM_OPTS="${JVM_OPTS} -Delasticsearch.config.loader=com.strapdata.cassandra.k8s.ElasticConcatenatedEnvironmentLoader"
JVM_OPTS="${JVM_OPTS} -Delasticsearch.config=/usr/share/cassandra/elasticsearch.yml:/etc/cassandra/elasticsearch.yml:/etc/cassandra/elasticsearch.yml.d"
