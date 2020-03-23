ARG base_image

FROM ${base_image}

ARG cassandra_k8s_addons_jar

# nuke contents of /etc/cassandra and /var/lib/cassandra since they're injected by volume mounts
# remove curl and cqlsh config
# remove /etc/cassandra/jmxremote.password, rewritten with JMX_PASSWORD
# remove default keystore.jks
# add image config .d directories
# add cassandra JMX readwrite permission
RUN rm -rf /var/lib/cassandra/* /root/.curlrc /root/.cassandra/cqlshrc /etc/cassandra/jmxremote.password /etc/cassandra/keystore.jks && \
    mkdir /etc/cassandra/cassandra.yaml.d && \
    mkdir /etc/cassandra/cassandra-env.sh.d && \
    mkdir /etc/cassandra/jvm.options.d && \
    mkdir /etc/cassandra/logback.xml.d && \
    mkdir /etc/cassandra/elasticsearch.yml.d && \
    echo "cassandra readwrite" >> /usr/lib/jvm/java-8-openjdk-amd64/jre/lib/management/jmxremote.access

# authorize cassandra (and all non-root user) to change system limits (ulimit) using
# ulimit can not be set at the host level because, when user is changed from root to cassandra, it lost existing limits.
# ulimit has to be set after the user has changes, but this require some capabilities to be set on the entrypoint executable,
# which is bash actually...
# unfortunalty, this does not work with unpriviledged mode... TODO: we'll have to find a workaround if we want to support unpriviledged setup
COPY dagi /usr/local/bin/
# Add openjdk-8-jdk-headless for jstat, but require to disable -XX:+PerfDisableSharedMem
RUN dagi  cpio libcap2-bin sysstat net-tools iotop dstat htop strace linux-perf && setcap cap_ipc_lock=+ep /bin/bash && setcap cap_sys_resource=+ep /bin/bash

COPY cassandra /usr/sbin/cassandra
COPY cassandra.in.sh /usr/share/cassandra/
COPY nodetool /usr/bin/
COPY jvm.options /etc/cassandra/

# this file is sourced from the entrypoint.sh
COPY systune.sh /

COPY ${cassandra_k8s_addons_jar} /usr/share/cassandra/lib

ADD default-config /etc/cassandra

# Because elassandra cannot run as root, authorize the entry-point.sh to copy files
RUN chown -R cassandra:cassandra /etc/cassandra /usr/share/cassandra

VOLUME /etc/cassandra

# wrap the entrtpoint
COPY elassandra-node-entrypoint.sh /usr/local/bin/elassandra-node-entrypoint.sh
RUN ln -s usr/local/bin/elassandra-node-entrypoint.sh /elassandra-node-entrypoint.sh # backwards compat
ENTRYPOINT ["elassandra-node-entrypoint.sh"]