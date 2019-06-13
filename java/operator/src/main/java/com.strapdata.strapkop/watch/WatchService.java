package com.strapdata.strapkop.watch;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.strapdata.model.Key;
import com.squareup.okhttp.Call;
import com.strapdata.strapkop.OperatorConfig;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.ApiResponse;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1Status;
import io.kubernetes.client.util.Watch;
import io.reactivex.subjects.BehaviorSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Init a cache from k8s ResourceListT and catch watch events<ResourceT> to update it and publish in a reactive subject.
 * @param <ResourceT>
 * @param <ResourceListT>
 */
public abstract class WatchService<ResourceT, ResourceListT> extends AbstractExecutionThreadService {
    private final Logger logger = LoggerFactory.getLogger(WatchService.class);

    private final TypeToken<ResourceT> resourceType = new TypeToken<ResourceT>(getClass()) {};
    private final TypeToken<ResourceListT> resourceListType = new TypeToken<ResourceListT>(getClass()) {};
    
    private final ApiClient apiClient;
    protected final OperatorConfig config;

    private final Gson gson;
    private Call currentCall;

    private final BehaviorSubject<WatchEvent<ResourceT>> behaviorSubject = BehaviorSubject.create();
    private final Map<Key<ResourceT>, ResourceT> cache = new HashMap<>();
    private final CountDownLatch latch = new CountDownLatch(1);
    
    private enum ResponseType {
        ADDED,
        MODIFIED,
        DELETED,
        ERROR
    }
    
    public WatchService(final ApiClient apiClient, final OperatorConfig config) {
        this.apiClient = apiClient;
        this.config = config;
        this.gson = apiClient.getJSON().getGson();
        apiClient.getHttpClient().setReadTimeout(1, TimeUnit.MINUTES);
    }

    public BehaviorSubject<WatchEvent<ResourceT>> getSubject() {
        return this.behaviorSubject;
    }
    
    @Override
    protected String serviceName() {
        return WatchService.class.getSimpleName() + " (" + resourceType.getRawType().getSimpleName() + ")";
    }

    @Override
    protected void run() throws Exception {
        while (isRunning()) {
            final String listResourceVersion = syncResourceCache();
            watchResourceList(listResourceVersion);
        }
    }
    
    private void watchResourceList(final String listResourceVersion) throws ApiException, IOException {
        logger.debug("Watching resource list.");

        currentCall = listResources(null, listResourceVersion, true);

        try (final Watch<JsonObject> watch = Watch.createWatch(apiClient, currentCall, new TypeToken<Watch.Response<JsonObject>>() {}.getType())) {
            for (final Watch.Response<JsonObject> objectResponse : watch) {
                final ResponseType responseType = ResponseType.valueOf(objectResponse.type);

                if (responseType == ResponseType.ERROR) {
                    final V1Status status = gson.fromJson(objectResponse.object, new TypeToken<Watch.Response<V1Status>>() {}.getType());

                    logger.error("Resource list watch failed with {}.", status);
                    return; // force re-sync
                }

                final ResourceT resource = gson.fromJson(objectResponse.object, resourceType.getType());
                switch (responseType) {
                    case ADDED:
                    case MODIFIED:
                        put(resourceKey(resource), resource);
                        break;
                    case DELETED:
                        remove(resourceKey(resource));
                        break;
                }
            }
        } catch (final RuntimeException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof java.net.SocketTimeoutException) {
                logger.debug("Resource list watch expired. Re-syncing.");
                return; // restart the watch by forcing a re-sync
            }
            throw e;
        }
    }

    protected abstract Call listResources(final String continueToken, final String resourceVersion, final boolean watch) throws ApiException;
    protected abstract Collection<? extends ResourceT> resourceListItems(final ResourceListT resourceList);
    protected abstract V1ListMeta resourceListMetadata(final ResourceListT resourceList);

    protected abstract V1ObjectMeta resourceMetadata(final ResourceT resource);
    public Key<ResourceT> resourceKey(final ResourceT t) { return new Key<ResourceT>(resourceMetadata(t)); }

    /**
     * Collect k8s objects to initialize the cache
     * @return
     * @throws ApiException
     */
    private String syncResourceCache() throws ApiException {
        logger.debug("Synchronising local cache.");

        final List<ResourceT> resourcesList = new ArrayList<>();
        V1ListMeta lastMetadata = null;
        String continueToken = null;
        do {
            final ApiResponse<ResourceListT> apiResponse = apiClient.execute(listResources(continueToken, null, false), resourceListType.getType());

            // TODO: is it necessary to handle different response statuses here...

            final ResourceListT apiResponseData = apiResponse.getData();

            lastMetadata = resourceListMetadata(apiResponseData);
            resourcesList.addAll(resourceListItems(apiResponseData));
            continueToken = lastMetadata.getContinue();
        } while (!Strings.isNullOrEmpty(continueToken));

        sync(resourcesList);
        logger.debug("Local cache synchronised.");
        return lastMetadata.getResourceVersion();
    }

    void sync(final List<ResourceT> resourceList) {
        final Map<Key<ResourceT>, ResourceT> remoteResources = resourceList.stream()
            .collect(ImmutableMap.toImmutableMap(this::resourceKey, Function.identity()));

        // remove non-existent resources from local cache
        // (immutable copy to prevent concurrent modification, as Sets::difference is a view over the underlying set/map)
        ImmutableSet.copyOf(Sets.difference(cache.keySet(), remoteResources.keySet()))
            .forEach(cache::remove);

        // update resources in local cache
        remoteResources.forEach(cache::put);
        latch.countDown();
    }

    private void put(final Key<ResourceT> key, final ResourceT resource) {
        final ResourceT oldResource = cache.put(key, resource);
        if (oldResource == null) {
            // new resource (previously not in cache)
            logger.debug("Added resource to local cache. Posting added event.", key);
            behaviorSubject.onNext(new WatchEvent.Added<ResourceT>(resource));
            return;
        }

        final String oldResourceVersion = resourceMetadata(oldResource).getResourceVersion();
        final String newResourceVersion = resourceMetadata(resource).getResourceVersion();
        if (!oldResourceVersion.equals(newResourceVersion)) {
            logger.debug("Remote and locally cached resource versions differ. Posting modified event. old = {}, new = {}.", oldResourceVersion, newResourceVersion);
            behaviorSubject.onNext(new WatchEvent.Modified<ResourceT>(oldResource, resource));
        }
    }

    private void remove(final Key<ResourceT> key) {
        logger.debug("Removing resource from local cache. Will post deleted event.");
        final ResourceT resource = cache.remove(key);
        behaviorSubject.onNext(new WatchEvent.Deleted<ResourceT>(resource));
    }

    public ResourceT get(final Key<ResourceT> key) {
        return cache.get(key);
    }

    @Override
    protected void triggerShutdown() {
        if (currentCall != null) {
            currentCall.cancel();
        }
    }
}
