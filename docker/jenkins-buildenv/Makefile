include ../../Makefile.inc

.PHONY: jenkins-buildenv
jenkins-buildenv:
	docker build \
		-t $(DOCKER_REPO)jenkins-buildenv \
		.

.PHONY: release
release: jenkins-buildenv
	docker push $(DOCKER_REPO)jenkins-buildenv

.DEFAULT_GOAL := jenkins-buildenv
