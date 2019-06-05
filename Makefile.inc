SUFFIX ?= ""
DOCKER_REPO ?= docker.repo.strapdata.com/elassandra-operator$(SUFFIX)/
GIT_COMMIT ?= $(shell git rev-parse --short HEAD)
NEXUS_USER ?= "user"
NEXUS_PASSWORD ?= "password"
NEXUS_CRED ?= "$(NEXUS_USER):$(NEXUS_PASSWORD)"
