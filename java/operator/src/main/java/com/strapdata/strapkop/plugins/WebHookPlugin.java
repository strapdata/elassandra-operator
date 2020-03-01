package com.strapdata.strapkop.plugins;

import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.DefaultHttpClient;
import io.micronaut.http.client.RxHttpClient;
import io.micronaut.http.uri.UriTemplate;
import io.reactivex.Completable;
import io.reactivex.Flowable;
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
        params.put("replicas", Integer.toString(dataCenter.getStatus().getReplicas()));
        return params;
    }

    /**
     * Call on each reconciliation
     *
     * @param dataCenter
     */
    @Override
    public Completable reconciled(DataCenter dataCenter) throws ApiException, StrapkopException {
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
    public Completable delete(DataCenter dataCenter) throws ApiException {
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
                        .ignoreElement();
            } catch (Exception e) {
                logger.error("Unexpected exception", e);
            }
        }
        return Completable.complete();
    }


    @Override
    public boolean isActive(final DataCenter dataCenter) {
        return true;
    }

    @Override
    public boolean reconcileOnParkState() {
        return false;
    }

    /**
     * Add/Remove keyspaces to/from the cqlKeyspaceManager for the dataCenter
     *
     * @param cqlKeyspaceManager
     * @param dataCenter
     */
    @Override
    public void syncKeyspaces(CqlKeyspaceManager cqlKeyspaceManager, DataCenter dataCenter) {

    }

    /**
     * Add/Remove roles to/from the cqlRoleManager for the dataCenter
     *
     * @param cqlRoleManager
     * @param dataCenter
     */
    @Override
    public void syncRoles(CqlRoleManager cqlRoleManager, DataCenter dataCenter) {

    }

    /**
     * Call on each reconciliation
     *
     * @param dataCenter
     */
    @Override
    public Completable reconcile(DataCenter dataCenter) throws ApiException, StrapkopException {
        return Completable.complete();
    }

}
