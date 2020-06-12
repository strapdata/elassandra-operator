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

import javax.inject.Singleton;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.Callable;

@Singleton
public class StatefulsetCache extends Cache<Key, TreeMap<String, V1StatefulSet>> {

    final K8sResourceUtils k8sResourceUtils;

    StatefulsetCache(final K8sResourceUtils k8sResourceUtils, final MeterRegistry meterRegistry) {
        this.k8sResourceUtils = k8sResourceUtils;
        meterRegistry.gaugeMapSize("cache.size", ImmutableList.of(new ImmutableTag("type", "statefulset")), this);
    }

    /**
     * Populate the cache if not event previously happen
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
}
