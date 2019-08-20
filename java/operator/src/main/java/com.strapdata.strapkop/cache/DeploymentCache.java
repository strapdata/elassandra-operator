package com.strapdata.strapkop.cache;

import com.strapdata.model.Key;
import io.kubernetes.client.models.V1Deployment;

import javax.inject.Singleton;

// TODO: this pipeline is not used yet
@Singleton
public class DeploymentCache extends Cache<Key, V1Deployment> {
}
