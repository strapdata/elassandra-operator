/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.event;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.strapdata.strapkop.pipeline.K8sWatchResourceAdapter;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.ApiResponse;
import io.kubernetes.client.util.Watch;
import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;

import static com.strapdata.strapkop.event.K8sWatchEvent.Type.ERROR;
import static com.strapdata.strapkop.event.K8sWatchEvent.Type.INITIAL;

/**
 * A Event source for kubernetes resources.
 *
 * @param <ResourceT>
 * @param <ResourceListT>
 */
@SuppressWarnings("UnstableApiUsage")
public class K8sWatchEventSource<ResourceT, ResourceListT, Key> implements EventSource<K8sWatchEvent<ResourceT>> {

    private final Logger logger = LoggerFactory.getLogger(K8sWatchEventSource.class);

    private final ApiClient watchClient;
    private final K8sWatchResourceAdapter<ResourceT, ResourceListT, Key> adapter;
    private final Gson gson;

    private String lastResourceVersion = null;

    public K8sWatchEventSource(final @Named("watchClient") ApiClient watchClient,
                               final K8sWatchResourceAdapter<ResourceT, ResourceListT, Key> adapter) {
        this.watchClient = watchClient;
        this.adapter = adapter;
        this.gson = watchClient.getJSON().getGson();
    }

    /**
     * Create a cold observable containing the existing resources first, then watching for modifications
     *
     * @return a cold observable
     * @throws ApiException
     */
    @Override
    public Observable<K8sWatchEvent<ResourceT>> createObservable() throws ApiException {

        logger.debug("(re)creating k8s event observable for {}", this.adapter.getName());

        // if last resource version is not null, restart watching where we stopped
        if (lastResourceVersion != null) {
            return createWatchObservable();
        }

        // otherwise take a snapshot of the current state, then watch
        return Observable.concat(createInitialObservable(), createWatchObservable());
    }

    /**
     * Fetch initial existing resource and create a cold observable out of it
     *
     * @return a cold observable
     * @throws ApiException
     */
    private Observable<K8sWatchEvent<ResourceT>> createInitialObservable() throws ApiException {
        logger.debug("Fetching existing k8s resources synchronously : {}", adapter.getName());
        try {
            final ApiResponse<ResourceListT> apiResponse = watchClient.execute(adapter.createListApiCall(false, null), adapter.getResourceListType());
            // TODO: is it necessary to handle different response statuses here...
            final ResourceListT resourceList = apiResponse.getData();
            logger.info("Fetched {} existing {}", adapter.getListItems(resourceList).size(), adapter.getName());
            lastResourceVersion = adapter.getListMetadata(resourceList).getResourceVersion();
            return Observable.fromIterable(adapter.getListItems(resourceList)).map(resource -> new K8sWatchEvent<>(INITIAL, resource, lastResourceVersion));
        } catch(Exception e) {
            logger.error("error", e);
            throw e;
        }
    }

    /**
     * Create a cold observable out of a k8s watch
     *
     * @return a cold observable
     * @throws ApiException
     */
    private Observable<K8sWatchEvent<ResourceT>> createWatchObservable() throws ApiException {
        logger.debug("Creating k8s watch for resource : {}", adapter.getName());
        final Watch<JsonObject> watch = Watch.createWatch(
                watchClient.setReadTimeout(0), // watch client continous read, see https://github.com/kubernetes-client/java/issues/178
                adapter.createListApiCall(Boolean.TRUE, lastResourceVersion),
                new TypeToken<Watch.Response<JsonObject>>() {}.getType());
        return Observable.fromIterable(watch)
                .observeOn(Schedulers.io()).observeOn(Schedulers.io()) // blocking io seemed to happen on computational thread...
                .doOnError(t -> {
                    if (t.getCause() instanceof java.net.SocketTimeoutException) {
                        logger.trace("Watcher for adapter '{}' receive a socket timeout", adapter.getClass().getName(), t);
                        // ignore read timeout
                        return;
                    }
                    logger.warn("Watcher for adapter receive an error", t);
                })
                .map(this::objectJsonToEvent)
                .doFinally(watch::close);
    }

    /**
     * Transform a raw ObjectJson into a Event ready to be published by the observable
     *
     * @param response whatever ObjectJson returned by k8s api
     * @return the event
     */
    private K8sWatchEvent<ResourceT> objectJsonToEvent(Watch.Response<JsonObject> response) {
        final K8sWatchEvent.Type type = K8sWatchEvent.Type.valueOf(response.type);
        ResourceT resource = null;

        if (type == ERROR) {
            logger.error("{} list watch failed with status={} object={}.", adapter.getName(), response.status, response.object);
        } else {
            // TODO: unit test with bad a datacenter CRD causing JsonSyntaxException
            try {
                resource = gson.fromJson(response.object, adapter.getResourceType());
                lastResourceVersion = adapter.getMetadata(resource).getResourceVersion();
            } catch(JsonSyntaxException e) {
                logger.warn("lastResourceVersion={} unrecoverable JSON syntax exception for type={}: {}", lastResourceVersion, type, e.getMessage());
                // inc version to ignore it on next retry
                lastResourceVersion = Long.toString( Long.parseLong(lastResourceVersion) + 1);
            }
        }

        K8sWatchEvent<ResourceT> watchEvent = new K8sWatchEvent<ResourceT>()
                .setType(type)
                .setResource(resource)
                .setLastResourceVersion(lastResourceVersion);
        logger.trace("new event={} lastResourceVersion={} type={} resource={}", watchEvent, lastResourceVersion, type, resource);
        return watchEvent;
    }
}
