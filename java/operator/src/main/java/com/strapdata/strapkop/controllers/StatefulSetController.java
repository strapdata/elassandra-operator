package com.strapdata.strapkop.controllers;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateAction;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1StatefulSet;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.reactivex.Completable;

import javax.inject.Inject;
import java.io.IOException;


@Controller("/statefulset")
public class StatefulSetController {

    @Inject
    private K8sResourceUtils k8sResourceUtils;

    @Inject
    private ApplicationContext context;

    public StatefulSetController() {
    }

    /**
     * Returns the rack statefulset manifest
     * @param namespace
     * @param dataCenterName
     * @param zoneName
     */
    @Get("/{namespace}/{dataCenterName}/{zoneName}")
    public V1StatefulSet get(String namespace, String dataCenterName, String zoneName) throws ApiException, IOException, StrapkopException {
        DataCenter dc = k8sResourceUtils.readDatacenter(new Key(namespace, dataCenterName));
        DataCenterUpdateAction dataCenterUpdateAction = context.createBean(DataCenterUpdateAction.class, dc);
        return dataCenterUpdateAction.builder.buildRackStatefulSet(zoneName, 0);
    }

    /**
     * Force Statefulset update
     * @param namespace
     * @param dataCenterName
     * @return
     */
    @Post("/{namespace}/{dataCenterName}")
    public Completable update(String namespace, String dataCenterName) throws Exception {
        DataCenter dc = k8sResourceUtils.readDatacenter(new Key(namespace, dataCenterName));
        DataCenterUpdateAction dataCenterUpdateAction = context.createBean(DataCenterUpdateAction.class, dc);
        return dataCenterUpdateAction.reconcileDataCenter();
    }
}
