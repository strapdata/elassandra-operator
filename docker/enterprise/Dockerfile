ARG cassandra_image

FROM ${cassandra_image}

ARG enterprise_version
ARG nexus_cred

COPY install-enterprise /tmp/install-enterprise

RUN /tmp/install-enterprise ${enterprise_version} ${nexus_cred}

