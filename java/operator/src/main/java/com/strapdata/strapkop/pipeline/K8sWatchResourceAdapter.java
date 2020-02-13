package com.strapdata.strapkop.pipeline;

import com.squareup.okhttp.Call;
import com.strapdata.strapkop.model.Key;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.models.V1ObjectMeta;

import java.lang.reflect.Type;
import java.util.Collection;

public abstract class K8sWatchResourceAdapter<ResourceT, ResourceListT> {
    public String getName() {
        return getResourceType().getTypeName();
    }
    public abstract Type getResourceType();
    public abstract Type getResourceListType();
    public abstract Call createListApiCall(final boolean watch, final String resourceVersion) throws ApiException;
    public abstract Key getKey(final ResourceT resource);
    public abstract V1ObjectMeta getMetadata(final ResourceT resource);
    public abstract Collection<? extends ResourceT> getListItems(final ResourceListT list);
    public abstract V1ListMeta getListMetadata(final ResourceListT list);
}
