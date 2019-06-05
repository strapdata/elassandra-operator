include ../base-openjre/Makefile

CASSANDRA_VERSION := 6.2.3.13

CASSANDRA_K8S_ADDONS_VERSION := 1.0-SNAPSHOT
CASSANDRA_K8S_ADDONS_JAR := cassandra-k8s-addons-$(CASSANDRA_K8S_ADDONS_VERSION).jar


# Cassandra/K8s Addons
ifeq ($(CASSANDRA_K8S_ADDONS_VERSION:%-SNAPSHOT=SNAPSHOT),SNAPSHOT)
# mark addons-jar target as .PHONY to force a re-copy on every build
$(info cassandra-k8s-addons SNAPSHOT version specified ($(CASSANDRA_K8S_ADDONS_VERSION)). Marking JAR target as .PHONY. This will re-download the artifact.)
.PHONY: $(CASSANDRA_K8S_ADDONS_JAR)
endif

$(CASSANDRA_K8S_ADDONS_JAR):
	mvn dependency:copy -Dartifact=com.instaclustr.cassandra-operator:cassandra-k8s-addons:$(CASSANDRA_K8S_ADDONS_VERSION) -DoutputDirectory=. -Dmdep.stripClassifier=true


# Cassandra
.PHONY: cassandra
cassandra: $(CASSANDRA_K8S_ADDONS_JAR)
	docker build \
		--build-arg cassandra_version=$(CASSANDRA_VERSION) \
		--build-arg cassandra_k8s_addons_jar=$(CASSANDRA_K8S_ADDONS_JAR) \
		--build-arg openjre_image="$(OPENJRE_IMAGE)" \
		-t $(DOCKER_REPO)elassandra \
		-t $(DOCKER_REPO)elassandra:$(CASSANDRA_VERSION) \
		.

.PHONY: release-cassandra
release-cassandra:
	docker push $(DOCKER_REPO)elassandra
	docker push $(DOCKER_REPO)elassandra:$(CASSANDRA_VERSION)
	docker tag $(DOCKER_REPO)elassandra:$(CASSANDRA_VERSION) $(DOCKER_REPO)elassandra:$(GIT_COMMIT)
	docker push $(DOCKER_REPO)elassandra:$(GIT_COMMIT)

.PHONY: clean
clean:
	rm $(CASSANDRA_K8S_ADDONS_JAR)

.DEFAULT_GOAL := cassandra
