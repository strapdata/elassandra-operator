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

import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateAction;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.DefaultHttpClient;
import io.micronaut.http.client.RxHttpClient;
import io.micronaut.http.uri.UriTemplate;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import org.elasticsearch.common.Strings;

import javax.inject.Singleton;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Manage user keyspaces and roles
 */
@Singleton
public class WebHookPlugin extends AbstractPlugin {

    public WebHookPlugin(final ApplicationContext context,
                         K8sResourceUtils k8sResourceUtils,
                         AuthorityManager authorityManager,
                         CoreV1Api coreApi,
                         AppsV1Api appsApi,
                         OperatorConfig operatorConfig,
                         MeterRegistry meterRegistry) {
        super(context, k8sResourceUtils, authorityManager, coreApi, appsApi, operatorConfig, meterRegistry);
    }

    RxHttpClient buildClient(String webhook) throws MalformedURLException {
        return new DefaultHttpClient(new URL(webhook));
    }

    Map<String, String> buildParams(DataCenter dataCenter) {
        Map<String, String> params = new HashMap<>();
        params.put("namespace", dataCenter.getMetadata().getNamespace());
        params.put("clusterName", dataCenter.getSpec().getClusterName());
        params.put("datacenterName", dataCenter.getSpec().getDatacenterName());
        params.put("phase", dataCenter.getStatus().getPhase().name());
        params.put("readyReplicas", Integer.toString(dataCenter.getStatus().getReadyReplicas()));
        params.put("replicas", Integer.toString(dataCenter.getSpec().getReplicas()));
        return params;
    }

    /**
     * Call on each reconciliation
     *
     * @param dataCenter
     */
    @Override
    public Completable reconciled(DataCenter dataCenter) throws StrapkopException {
        if (!Strings.isNullOrEmpty(dataCenter.getSpec().getWebHookUrl())) {
            try {
                String uri = UriTemplate.of(dataCenter.getSpec().getWebHookUrl()).expand(buildParams(dataCenter));
                RxHttpClient client = buildClient(dataCenter.getSpec().getWebHookUrl());
                logger.debug("uri={}", uri);
                HttpRequest<?> req = HttpRequest.GET(uri);
                Flowable<HttpStatus> flowable = client.retrieve(req, HttpStatus.class);
                return flowable.firstElement()
                        .map(httpStatus -> {
                            logger.info("GET {}={}", uri, httpStatus.getCode());
                            return httpStatus;
                        })
                        .ignoreElement();
            } catch (Exception e) {
                logger.error("Unexpected exception", e);
            }
        }
        return Completable.complete();
    }

    /**
     * Call when deleting the elassandra datacenter
     *
     * @param dataCenter
     */
    @Override
    public Single<Boolean> delete(DataCenter dataCenter) {
        if (!Strings.isNullOrEmpty(dataCenter.getSpec().getWebHookUrl())) {
            try {
                String uri = UriTemplate.of(dataCenter.getSpec().getWebHookUrl()).expand(buildParams(dataCenter));
                RxHttpClient client = buildClient(dataCenter.getSpec().getWebHookUrl());
                logger.debug("uri={}", uri);
                HttpRequest<?> req = HttpRequest.DELETE(uri);
                Flowable<HttpStatus> flowable = client.retrieve(req, HttpStatus.class);
                return flowable.firstElement()
                        .map(httpStatus -> {
                            logger.info("DELETE {}={}", uri, httpStatus.getCode());
                            return httpStatus;
                        })
                        .ignoreElement()
                        .toSingleDefault(false);
            } catch (Exception e) {
                logger.error("Unexpected exception", e);
            }
        }
        return Single.just(false);
    }

    @Override
    public boolean isActive(final DataCenter dataCenter) {
        return true;
    }

    /**
     * Call on each reconciliation
     *
     * @param dataCenterUpdateAction
     */
    @Override
    public Single<Boolean> reconcile(DataCenterUpdateAction dataCenterUpdateAction) throws StrapkopException {
        return Single.just(false);
    }

}
