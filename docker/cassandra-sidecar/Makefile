include ../base-openjre/Makefile

CASSANDRA_SIDECAR_VERSION := 1.0-SNAPSHOT
CASSANDRA_SIDECAR_JAR := cassandra-sidecar-$(CASSANDRA_SIDECAR_VERSION).jar

ifeq ($(CASSANDRA_SIDECAR_VERSION:%-SNAPSHOT=SNAPSHOT),SNAPSHOT)
# mark sidecar-jar target as .PHONY to force a re-copy on every build
$(info cassandra-sidecar SNAPSHOT version specified ($(CASSANDRA_SIDECAR_VERSION)). Marking JAR target as .PHONY. This will re-download the artifact.)
.PHONY: $(CASSANDRA_SIDECAR_JAR)
endif

$(CASSANDRA_SIDECAR_JAR):
	mvn dependency:copy -Dartifact=com.instaclustr.cassandra-operator:cassandra-sidecar:$(CASSANDRA_SIDECAR_VERSION) -DoutputDirectory=. -Dmdep.stripClassifier=true
	
.PHONY: cassandra-sidecar
cassandra-sidecar: $(CASSANDRA_SIDECAR_JAR)
	docker build \
		--build-arg cassandra_sidecar_jar=$(CASSANDRA_SIDECAR_JAR) \
		--build-arg openjre_image="$(OPENJRE_IMAGE)" \
		-t $(DOCKER_REPO)elassandra-sidecar \
		-t $(DOCKER_REPO)elassandra-sidecar:$(CASSANDRA_SIDECAR_VERSION) \
		.

.PHONY: release-cassandra-sidecar
release-cassandra-sidecar:
	docker push $(DOCKER_REPO)elassandra-sidecar
	docker push $(DOCKER_REPO)elassandra-sidecar:$(CASSANDRA_SIDECAR_VERSION)
	docker tag  $(DOCKER_REPO)elassandra-sidecar:$(CASSANDRA_SIDECAR_VERSION) $(DOCKER_REPO)elassandra-sidecar:$(GIT_COMMIT)
	docker push $(DOCKER_REPO)elassandra-sidecar:$(GIT_COMMIT)

.PHONY: clean
clean:
	rm $(CASSANDRA_SIDECAR_JAR)


.DEFAULT_GOAL := cassandra-sidecar
