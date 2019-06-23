package com.strapdata.strapkop.cache;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;

import javax.inject.Singleton;

@Singleton
public class DataCenterCache extends Cache<Key, DataCenter> {
}
