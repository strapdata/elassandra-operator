
.PHONY: all
all: java docker

.PHONY: java
java:
	cd java && mvn install
	
.PHONY: docker
docker: java
	$(MAKE) -C $@

.PHONY: docker-release
docker-release:
	$(MAKE) release -C docker


.DEFAULT_GOAL := all
