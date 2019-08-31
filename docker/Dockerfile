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

# authorize cassandra (and all non-root user) to change system limits (ulimit) using
# ulimit can not be set at the host level because, when user is changed from root to cassandra, it lost existing limits.
# ulimit has to be set after the user has changes, but this require some capabilities to be set on the entrypoint executable,
# which is bash actually...
# unfortunalty, this does not work with unpriviledged mode... TODO: we'll have to find a workaround if we want to support unpriviledged setup
RUN dagi libcap2-bin && setcap cap_ipc_lock=+ep /bin/bash && setcap cap_sys_resource=+ep /bin/bash
# this file is sourced from the entrypoint.sh
COPY systune.sh /

COPY ${cassandra_k8s_addons_jar} /usr/share/cassandra/lib

ADD default-config /etc/cassandra

# Because elassandra cannot run as root, authorize the entry-point.sh to copy files
RUN chown -R cassandra:cassandra /etc/cassandra /usr/share/cassandra

VOLUME /etc/cassandra