package com.strapdata.strapkop.cache;

import com.strapdata.strapkop.model.Key;
import io.kubernetes.client.models.V1Deployment;

import javax.inject.Singleton;

@Singleton
public class DeploymentCache extends Cache<Key, V1Deployment> {
}
