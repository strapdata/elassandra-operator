package com.strapdata.strapkop.cache;

import io.kubernetes.client.models.V1Node;

import javax.inject.Singleton;

@Singleton
public class NodeCache extends Cache<String, V1Node> {

}
