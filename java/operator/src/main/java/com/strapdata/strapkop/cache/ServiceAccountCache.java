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
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ServiceAccount;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.reactivex.Single;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Objects;
import java.util.concurrent.Callable;

@Singleton
public class ServiceAccountCache extends Cache<Key, V1ServiceAccount> {

    @Inject
    K8sResourceUtils k8sResourceUtils;

    @Inject
    MeterRegistry meterRegistry;


    @PostConstruct
    public void initGauge() {
        meterRegistry.gaugeMapSize("cache.size", ImmutableList.of(new ImmutableTag("type", "serviceaccount")), this);
    }


    public void purgeServiceAccount(final DataCenter dc) {
        this.entrySet().removeIf(e ->
                Objects.equals(e.getValue().getMetadata().getLabels().get(OperatorLabels.DATACENTER), dc.getSpec().getDatacenterName()) &&
                        Objects.equals(e.getValue().getMetadata().getLabels().get(OperatorLabels.CLUSTER), dc.getSpec().getClusterName()) &&
                        Objects.equals(e.getKey().getNamespace(), dc.getMetadata().getNamespace()));
    }

    public Single<V1ServiceAccount> load(String serviceAccountName, String namespace) {
        return Single.fromCallable(new Callable<V1ServiceAccount>() {
            @Override
            public V1ServiceAccount call() throws Exception {
                Key key = new Key(serviceAccountName, namespace);
                return compute(key, (k,v) -> {
                    try {
                        return k8sResourceUtils.readNamespacedServiceAccount(k.getNamespace(), k.getName());
                    } catch (ApiException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        });
    }
}
