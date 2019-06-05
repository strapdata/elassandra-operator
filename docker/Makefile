IMAGES = base-openjre cassandra enterprise cassandra-sidecar cassandra-operator

.PHONY: all
all: $(IMAGES)

.PHONY: $(IMAGES)
$(IMAGES):
	$(MAKE) -C $@

cassandra enterprise cassandra-sidecar cassandra-operator: base-openjre

release:
	for sub in $(IMAGES) ; do \
		$(MAKE) -C $$sub release-$$sub  || exit 1; \
	done

.DEFAULT_GOAL := all
