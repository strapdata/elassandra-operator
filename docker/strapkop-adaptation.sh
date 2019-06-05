#!/bin/bash -xue

echoerr() { >&2 echo $@; }

dagi cpio

# package "cleanup"
mkdir -p /usr/share/cassandra/agents
mv /usr/share/cassandra/lib/jamm-0.3.0.jar /usr/share/cassandra/agents/jamm-0.3.0.jar
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

# install cassandra-exporter (Prometheus monitoring support)
(cd "/usr/share/cassandra/agents" &&
    curl -SLO "https://github.com/instaclustr/cassandra-exporter/releases/download/v0.9.6/cassandra-exporter-agent-0.9.6.jar" &&
    ln -s cassandra-exporter-agent-0.9.6.jar cassandra-exporter-agent.jar)

apt-get -y autoremove

rm "${BASH_SOURCE}"