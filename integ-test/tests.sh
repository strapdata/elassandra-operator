#!/usr/bin/env bash

integ-test/install_elassandra_operator.sh
integ-test/test-admission.sh
integ-test/test-reaper-registration.sh
integ-test/test-scaleup-park-unpark.sh
integ-test/test-multiple-dc-1ns.sh
integ-test/test-multiple-dc-2ns.sh
integ-test/test-rolling-upgrade.sh