#!/bin/bash

# TODO: moving this to the sidecar could be more reliable : true nodetool check with long-lived jmx connection

# the port to scan
case $1 in
9042)
   port=2352
   ;;
9200)
   port=23F0
   ;;
esac

exec grep "00000000:$port" /proc/net/tcp
