#!/usr/bin/env bash

set -xe
THIRDPARTY_DIR=build/thirdparty-libs/thirdparty-licenses

process() {
  if [[ "$1" = '#'* ]]; then
    return ;
  fi

  group=$(echo $1 | cut -d';' -f1)
  name=$(echo $1 | cut -d';' -f2)
  url=$(echo $1 | cut -d';' -f 3)
  echo $group $name $url
  wget $url -O $THIRDPARTY_DIR/tmp.x
  mkdir -p $THIRDPARTY_DIR/${group}_${name}
  if [[ $url = *.zip ]]; then
    unzip $THIRDPARTY_DIR/tmp.x -d $THIRDPARTY_DIR/${group}_${name} >/dev/null
  elif [[ $url = *.tar.gz ]]; then
    tar xzf $THIRDPARTY_DIR/tmp.x -C $THIRDPARTY_DIR/${group}_${name} >/dev/null
  fi
  rm $THIRDPARTY_DIR/tmp.x
}

if [ ! -d "$THIRDPARTY_DIR" ]
then
  mkdir -p $THIRDPARTY_DIR
  while read line ; do process "$line" ; done < $1
fi

