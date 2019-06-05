include ../cassandra/Makefile

.PHONY: enterprise
enterprise:
	docker build \
	  --build-arg cassandra_image=$(DOCKER_REPO)elassandra:$(CASSANDRA_VERSION) \
		--build-arg enterprise_version=$(CASSANDRA_VERSION) \
		--build-arg nexus_cred=$(NEXUS_CRED) \
		-t $(DOCKER_REPO)enterprise \
		-t $(DOCKER_REPO)enterprise:$(CASSANDRA_VERSION) \
		.

.PHONY: release-enterprise
release-enterprise:
	docker push $(DOCKER_REPO)enterprise
	docker push $(DOCKER_REPO)enterprise:$(CASSANDRA_VERSION)
	docker tag $(DOCKER_REPO)enterprise:$(CASSANDRA_VERSION) $(DOCKER_REPO)enterprise:$(GIT_COMMIT)
	docker push $(DOCKER_REPO)enterprise:$(GIT_COMMIT)

.DEFAULT_GOAL := enterprise
