package com.strapdata.strapkop.cache;

import com.strapdata.strapkop.model.Key;
import io.kubernetes.client.models.V1StatefulSet;

import javax.inject.Singleton;

@Singleton
public class StatefulsetCache extends Cache<Key, V1StatefulSet> {
}
