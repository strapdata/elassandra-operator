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

package com.strapdata.strapkop.plugins;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateAction;
import io.kubernetes.client.openapi.ApiException;
import io.reactivex.Completable;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * Plugin registry (hardcoded)
 */
@Singleton
public class PluginRegistry {

    private final Logger logger = LoggerFactory.getLogger(PluginRegistry.class);

    final List<Plugin> plugins;

    public PluginRegistry(final ReaperPlugin reaperPlugin,
                          final KibanaPlugin kibanaPlugin,
                          final ManagedKeyspacePlugin managedKeyspacePlugin,
                          final WebHookPlugin webHookPlugin) {
        this.plugins = ImmutableList.of(reaperPlugin, kibanaPlugin, managedKeyspacePlugin, webHookPlugin);
    }

    public List<Plugin> plugins() {
        return this.plugins;
    }

    public Completable deleteAll(DataCenter dc) {
        List<Completable> pluginCompletables = new ArrayList<>();
        for (Plugin plugin : plugins) {
            try {
                pluginCompletables.add(plugin.delete(dc).ignoreElement()
                        .onErrorResumeNext(t -> {
                            logger.warn("plugin={} delete failed, error={}", plugin.getClass().getName(), t.toString());
                            return Completable.complete();
                        }));
            } catch (Exception e) {
                logger.error("Plugin class=" + plugin.getClass().getSimpleName() + " reconciliation failed:", e);
            }
        }
        return Completable.mergeArray(pluginCompletables.toArray(new Completable[pluginCompletables.size()]));
    }

    public Single<Boolean> reconcileAll(DataCenterUpdateAction dataCenterUpdateAction) {
        List<Single<Boolean>> pluginSingles = new ArrayList<>();
        for (Plugin plugin : plugins) {
            try {
                pluginSingles.add(plugin.reconcile(dataCenterUpdateAction)
                        .onErrorResumeNext(t -> {
                            if (t instanceof ApiException) {
                                ApiException e = (ApiException) t;
                                logger.warn("datacenter=" + dataCenterUpdateAction.dataCenter.id() +
                                        " plugin=" + plugin.getClass().getName() +
                                        " reconcile failed, error code=" + e.getCode() + " body="+ e.getResponseBody(), e);
                            } else {
                                logger.warn("datacenter=" + dataCenterUpdateAction.dataCenter.id() +
                                        " plugin=" + plugin.getClass().getName() +
                                        " reconcile failed, error:", t);
                            }
                            return Single.just(false);
                        }));
            } catch (Exception e) {
                logger.error("Plugin class=" + plugin.getClass().getSimpleName() + " reconciliation failed:", e);
            }
        }
        return pluginSingles.size() == 0 ?
                Single.just(false) :
                Single.zip(pluginSingles, (Object[] results) -> {
                    boolean result = false;
                    for (Object r : results) {
                        result = result || (Boolean) r;
                    }
                    return result;
                });
    }
}
