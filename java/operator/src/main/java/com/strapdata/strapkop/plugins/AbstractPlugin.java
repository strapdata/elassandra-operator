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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.reactivex.Completable;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public abstract class AbstractPlugin implements Plugin {
    final static Logger logger = LoggerFactory.getLogger(AbstractPlugin.class);

    final ApplicationContext context;
    final K8sResourceUtils k8sResourceUtils;
    final AuthorityManager authorityManager;
    final CoreV1Api coreApi;
    final AppsV1Api appsApi;
    final OperatorConfig operatorConfig;
    final MeterRegistry meterRegistry;

    public AbstractPlugin(final ApplicationContext context,
                          K8sResourceUtils k8sResourceUtils,
                          AuthorityManager authorityManager,
                          CoreV1Api coreApi,
                          AppsV1Api appsApi,
                          OperatorConfig operatorConfig,
                          MeterRegistry meterRegistry) {
        this.context = context;
        this.k8sResourceUtils = k8sResourceUtils;
        this.authorityManager = authorityManager;
        this.coreApi = coreApi;
        this.appsApi = appsApi;
        this.operatorConfig = operatorConfig;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Call when the datacenter is reconcilied after a start or scale up/downn
     *
     * @param dataCenter
     */
    @Override
    public Completable reconciled(DataCenter dataCenter) throws StrapkopException {
        return Completable.complete();
    }

    public Map<String, String> deploymentLabelSelector(DataCenter dc) {
        return ImmutableMap.of();
    }

    public Single<List<V1Deployment>> listDeployments(DataCenter dc) {
        return Single.fromCallable(new Callable<List<V1Deployment>>() {
            @Override
            public List<V1Deployment> call() throws Exception {
                return Lists.newArrayList(k8sResourceUtils.listNamespacedDeployment(
                        dc.getMetadata().getNamespace(), null, OperatorLabels.toSelector(deploymentLabelSelector(dc))));
            }
        });
    }
}
