ARG openjre_image

FROM ${openjre_image}

ARG cassandra_version=3.11.2
ARG cassandra_k8s_addons_jar

COPY install-cassandra /tmp/install-cassandra

RUN /tmp/install-cassandra ${cassandra_version}

COPY entry-point /usr/bin/entry-point

COPY cassandra /usr/sbin/cassandra
COPY nodetool /usr/bin/nodetool
COPY cassandra.in.sh /usr/share/cassandra/
COPY cql-readiness-probe /usr/bin/cql-readiness-probe
COPY tcp-readiness-probe /usr/bin/tcp-readiness-probe

COPY ${cassandra_k8s_addons_jar} /usr/share/cassandra/lib

ADD default-config /etc/cassandra

# Because elassandra cannot run as root, authorize the entry-point.sh to copy files
RUN chown -R cassandra:cassandra /etc/cassandra /usr/share/cassandra

VOLUME /var/lib/cassandra
VOLUME /etc/cassandra

ENV CASSANDRA_HOME /usr/share/cassandra
ENV CASSANDRA_CONF /etc/cassandra
ENV CASSANDRA_LOGDIR /var/log/cassandra
ENV CASSANDRA_DATA /var/lib/cassandra

# 7000: intra-node communication
# 7001: TLS intra-node communication
# 7199: JMX
# 9042: CQL
# 9160: thrift service
# 9200: elassandra HTTP
# 9300: elasticsearch transport
EXPOSE 7000 7001 7199 9042 9160 9200 9300

ENTRYPOINT ["/usr/bin/entry-point"]
