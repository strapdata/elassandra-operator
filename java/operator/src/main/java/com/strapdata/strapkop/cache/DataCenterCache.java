package com.strapdata.strapkop.cache;

import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;

import javax.inject.Singleton;

@Singleton
public class DataCenterCache extends Cache<Key, DataCenter> {

}
