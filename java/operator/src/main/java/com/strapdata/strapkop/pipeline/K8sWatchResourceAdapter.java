package com.strapdata.strapkop.pipeline;


import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import okhttp3.Call;

import java.lang.reflect.Type;
import java.util.Collection;

public abstract class K8sWatchResourceAdapter<ResourceT, ResourceListT, K> {
    public String getName() {
        return getResourceType().getTypeName();
    }
    public abstract Type getResourceType();
    public abstract Type getResourceListType();
    public abstract Call createListApiCall(final boolean watch, final String resourceVersion) throws ApiException;
    public abstract K getKey(final ResourceT resource);
    public abstract V1ObjectMeta getMetadata(final ResourceT resource);
    public abstract Collection<? extends ResourceT> getListItems(final ResourceListT list);
    public abstract V1ListMeta getListMetadata(final ResourceListT list);
}
