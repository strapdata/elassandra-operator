package com.strapdata.strapkop.k8s;

import io.kubernetes.client.openapi.ApiException;

@FunctionalInterface
public interface K8sSupplier<T> {
    public T get() throws ApiException;
}