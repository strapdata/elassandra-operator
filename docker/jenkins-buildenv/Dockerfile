FROM debian:stretch

COPY dagi /usr/local/bin/

RUN dagi locales gnupg2 dirmngr lsb-release curl git \
    openjdk-8-jdk-headless maven apt-transport-https sudo

# add gloud sdk repo
RUN export CLOUD_SDK_REPO="cloud-sdk-$(lsb_release -c -s)" && \
    echo "deb http://packages.cloud.google.com/apt $CLOUD_SDK_REPO main" | tee -a /etc/apt/sources.list.d/google-cloud-sdk.list && \
    curl https://packages.cloud.google.com/apt/doc/apt-key.gpg | apt-key add -

# add docker repo
RUN echo 'deb https://apt.dockerproject.org/repo debian-stretch main' >> /etc/apt/sources.list && \
    apt-key adv --no-tty --keyserver hkp://p80.pool.sks-keyservers.net:80 --recv-keys 58118E89F3A912897C070ADBF76221572C52609D

# add azure sdk repo
RUN export AZ_REPO=$(lsb_release -cs) && \
    echo "deb [arch=amd64] https://packages.microsoft.com/repos/azure-cli/ $AZ_REPO main" |  tee /etc/apt/sources.list.d/azure-cli.list && \
    curl -L https://packages.microsoft.com/keys/microsoft.asc | apt-key add -

# install docker, kubectl gcloug and azure cli
RUN dagi google-cloud-sdk docker-engine azure-cli kubectl

# intsall helm
RUN curl https://raw.githubusercontent.com/kubernetes/helm/master/scripts/get | bash

# add telepresence repo
RUN curl -s https://packagecloud.io/install/repositories/datawireio/telepresence/script.deb.sh | bash

# install telepresence
RUN dagi telepresence

RUN dagi jq make

# configure nexus maven repostitory
COPY settings.xml /root/.m2/settings.xml