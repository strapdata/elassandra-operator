#!/bin/bash -xue

echoerr() { >&2 echo $@; }

dagi cpio

# we will override the default cassandra.in.sh
mv /usr/share/cassandra/cassandra.in.sh /usr/share/cassandra/default-cassandra.in.sh

cp /etc/cassandra/hotspot_compiler /usr/share/cassandra/
cp /etc/cassandra/cassandra.yaml /usr/share/cassandra/
cp /etc/cassandra/elasticsearch.yml /usr/share/cassandra/

# move trigger directory. Is this useful ?
mv /etc/cassandra/triggers /usr/share/cassandra/triggers

# nuke contents of /etc/cassandra and /var/lib/cassandra since they're injected by volume mounts
rm -rf /etc/cassandra/* /var/lib/cassandra/*

# add image config .d directories
mkdir /etc/cassandra/cassandra.yaml.d
mkdir /etc/cassandra/cassandra-env.sh.d
mkdir /etc/cassandra/jvm.options.d
mkdir /etc/cassandra/logback.xml.d
mkdir /etc/cassandra/elasticsearch.yml.d

# remove curl and cqlsh config
rm -vf /root/.curlrc /root/.cassandra/cqlshrc

# remove tool cassandra.in.sh as it does not contains fragment yaml configuration loader.
# /usr/share/cassandra/cassandra.in.sh will be used instead
rm /usr/share/cassandra/tools/bin/cassandra.in.sh

# install the instaclustr cassandra-exporter (Prometheus monitoring support)
(cd "/usr/share/cassandra/agents" &&
    curl -SLO "https://github.com/instaclustr/cassandra-exporter/releases/download/v0.9.6/cassandra-exporter-agent-0.9.6.jar" &&
    ln -s cassandra-exporter-agent-0.9.6.jar cassandra-exporter-agent.jar)

# install the jmx-prometheus-exporter (Prometheus monitoring support)
(cd "/usr/share/cassandra/agents" &&
    curl -SLO "https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.12.0/jmx_prometheus_javaagent-0.12.0.jar" &&
    ln -s jmx_prometheus_javaagent-0.12.0.jar jmx_prometheus_javaagent.jar)

apt-get -y autoremove

rm "${BASH_SOURCE}"