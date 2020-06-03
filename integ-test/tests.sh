#!/usr/bin/env bash

source integ-test/test-lib.sh

init_cluster

integ-test/test-admission.sh
integ-test/test-scaleup-park-unpark-reaper.sh
integ-test/test-mutliple-dc.sh

delete_cluster