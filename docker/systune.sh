#!/usr/bin/env bash

# memlock unlimited
ulimit -l unlimited

# nofile 100000
ulimit -n 100000

# nproc 32768
ulimit -s 32768

# as (address space) unlimited
ulimit -v unlimited
