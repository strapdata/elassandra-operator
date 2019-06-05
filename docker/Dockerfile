ARG base_image

FROM ${base_image}

ARG cassandra_k8s_addons_jar

COPY dagi /usr/local/bin/

COPY strapkop-adaptation.sh /tmp/strapkop-adaptation.sh
RUN /tmp/strapkop-adaptation.sh

COPY strapkop-entrypoint.sh /usr/local/bin/strapkop-entrypoint.sh
RUN ln -s usr/local/bin/strapkop-entrypoint.sh /strapkop-entrypoint.sh # backwards compat
ENTRYPOINT ["strapkop-entrypoint.sh"]

COPY cassandra /usr/sbin/cassandra
COPY nodetool /usr/bin/nodetool
COPY cassandra.in.sh /usr/share/cassandra/
# replace ready-probe.sh
COPY ready-probe.sh /

COPY ${cassandra_k8s_addons_jar} /usr/share/cassandra/lib

ADD default-config /etc/cassandra

# Because elassandra cannot run as root, authorize the entry-point.sh to copy files
RUN chown -R cassandra:cassandra /etc/cassandra /usr/share/cassandra

VOLUME /etc/cassandra
