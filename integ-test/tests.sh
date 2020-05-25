#!/usr/bin/env bash

source $(dirname $0)/test-lib.sh

init_cluster

$(dirname $0)/test-scaleup-park-unpark-reaper.sh
$(dirname $0)/test-mutliple-dc.sh

delete_cluster