#!/usr/bin/env bash

# dump dick write cache settings
# Access timestamps disabled, and other options depending on the FS
# vm.dirty_ratio = 80 # from 40 $
# vm.dirty_background_ratio = 5 # from 10
# vm.dirty_expire_centisecs = 12000 # from 3000
# mount -o defaults,noatime,discard,nobarrier …
sysctl -a | grep dirty

# memlock unlimited
ulimit -l unlimited

# nofile 100000
ulimit -n 100000

# nproc 32768
ulimit -s 32768

# as (address space) unlimited
ulimit -v unlimited
