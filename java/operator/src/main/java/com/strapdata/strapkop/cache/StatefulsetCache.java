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
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.TreeMap;

@Singleton
public class StatefulsetCache extends Cache<Key, TreeMap<String, V1StatefulSet>> {

    @Inject
    MeterRegistry meterRegistry;

    @PostConstruct
    public void initGauge() {
        meterRegistry.gaugeMapSize("cache.size", ImmutableList.of(new ImmutableTag("type", "statefulset")), this);
    }

    public TreeMap<String, V1StatefulSet> update(V1StatefulSet sts) {
        final Key key = new Key(sts.getMetadata().getNamespace(), sts.getMetadata().getLabels().get(OperatorLabels.PARENT));
        final String zone = sts.getMetadata().getLabels().get(OperatorLabels.RACK);
        if (zone != null) {
            return compute(key, (k, v) -> {
                if (v == null) {
                    v = new TreeMap<>();
                }
                v.put(zone, sts);
                return v;
            });
        }
        return getOrDefault(key, new TreeMap<>());
    }

    public TreeMap<String, V1StatefulSet> updateIfAbsent(V1StatefulSet sts) {
        final Key key = new Key(sts.getMetadata().getNamespace(), sts.getMetadata().getLabels().get(OperatorLabels.PARENT));
        final String zone = sts.getMetadata().getLabels().get(OperatorLabels.RACK);
        if (zone != null) {
            return compute(key, (k, v) -> {
                if (v == null) {
                    v = new TreeMap<>();
                }
                v.putIfAbsent(zone, sts);
                return v;
            });
        }
        return getOrDefault(key, new TreeMap<>());
    }
}
