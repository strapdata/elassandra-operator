package com.strapdata.strapkop.cache;

import com.strapdata.strapkop.model.Key;
import io.kubernetes.client.models.V1Node;

import javax.inject.Singleton;

@Singleton
public class NodeCache extends Cache<Key, V1Node> {

}
