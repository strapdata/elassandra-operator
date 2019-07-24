package com.strapdata.strapkop.cache;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.strapdata.model.Key;
import io.vavr.Tuple2;

import javax.inject.Singleton;

@Singleton
public class CqlConnectionCache extends Cache<Key, Tuple2<Cluster, Session>> {
}
