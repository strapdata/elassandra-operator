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

package com.strapdata.strapkop.cache;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.reactivex.Single;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.Callable;

@Singleton
public class StatefulsetCache extends Cache<Key, TreeMap<String, V1StatefulSet>> {

    @Inject
    K8sResourceUtils k8sResourceUtils;

    @Inject
    MeterRegistry meterRegistry;


    @PostConstruct
    public void initGauge() {
        meterRegistry.gaugeMapSize("cache.size", ImmutableList.of(new ImmutableTag("type", "statefulset")), this);
    }

    /**
     * Populate the cache if empty
     * @param dataCenter
     * @return
     */
    public Single<TreeMap<String, V1StatefulSet>> loadIfAbsent(final DataCenter dataCenter) {
        return Single.fromCallable(new Callable<TreeMap<String, V1StatefulSet>>() {
            @Override
            public TreeMap<String, V1StatefulSet> call() throws Exception {
                Key key = new Key(dataCenter.getMetadata());
                return computeIfAbsent(key, k -> {
                    // Fetch existing statefulsets from k8s api and sort then by zone name
                    try {
                        final Iterable<V1StatefulSet> statefulSetsIterable = k8sResourceUtils.listNamespacedStatefulSets(
                                dataCenter.getMetadata().getNamespace(), null,
                                OperatorLabels.toSelector(OperatorLabels.datacenter(dataCenter)));
                        final TreeMap<String, V1StatefulSet> result = new TreeMap<>();
                        for (V1StatefulSet sts : statefulSetsIterable) {
                            final String zone = sts.getMetadata().getLabels().get(OperatorLabels.RACK);
                            if (zone == null) {
                                throw new StrapkopException(String.format(Locale.ROOT, "statefulset %s has no RACK label", sts.getMetadata().getName()));
                            }
                            if (result.containsKey(zone)) {
                                throw new StrapkopException(String.format(Locale.ROOT, "two statefulsets in the same zone=%s dc=%s", zone, dataCenter.getMetadata().getName()));
                            }
                            result.put(zone, sts);
                        }
                        return result;
                    } catch (ApiException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        });
    }

    /**
     * Force a cache update
     * @param dataCenter
     * @return
     */
    public Single<TreeMap<String, V1StatefulSet>> load(final DataCenter dataCenter) {
        return Single.fromCallable(new Callable<TreeMap<String, V1StatefulSet>>() {
            @Override
            public TreeMap<String, V1StatefulSet> call() throws Exception {
                Key key = new Key(dataCenter.getMetadata());
                return compute(key, (k,v) -> {
                    // Fetch existing statefulsets from k8s api and sort then by zone name
                    try {
                        final Iterable<V1StatefulSet> statefulSetsIterable = k8sResourceUtils.listNamespacedStatefulSets(
                                dataCenter.getMetadata().getNamespace(), null,
                                OperatorLabels.toSelector(OperatorLabels.datacenter(dataCenter)));
                        final TreeMap<String, V1StatefulSet> result = new TreeMap<>();
                        for (V1StatefulSet sts : statefulSetsIterable) {
                            final String zone = sts.getMetadata().getLabels().get(OperatorLabels.RACK);
                            if (zone == null) {
                                throw new StrapkopException(String.format(Locale.ROOT, "statefulset %s has no RACK label", sts.getMetadata().getName()));
                            }
                            if (result.containsKey(zone)) {
                                throw new StrapkopException(String.format(Locale.ROOT, "two statefulsets in the same zone=%s dc=%s", zone, dataCenter.getMetadata().getName()));
                            }
                            result.put(zone, sts);
                        }
                        return result;
                    } catch (ApiException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        });
    }
}
