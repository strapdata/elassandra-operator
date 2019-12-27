package com.strapdata.strapkop.controllers;

import com.strapdata.strapkop.k8s.K8sResourceUtils;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.annotation.Controller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;


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
    /*
    @Get("/{namespace}/{dataCenterName}/{zoneName}")
    public Single<V1StatefulSet> get(String namespace, String dataCenterName, String zoneName) throws ApiException, IOException, StrapkopException {
        return k8sResourceUtils.readDatacenter(new Key(namespace, dataCenterName))
                .map(dc -> context.createBean(DataCenterUpdateAction.class, dc).builder.buildStatefulSetRack(zoneName, 0));
    }
*/
    /**
     * Force Statefulset update
     * @param namespace
     * @param dataCenterName
     * @return
     */
    /*
    @Post("/{namespace}/{dataCenterName}")
    public Completable update(String namespace, String dataCenterName) throws Exception {
        return k8sResourceUtils.readDatacenter(new Key(namespace, dataCenterName))
                .flatMapCompletable(dc -> context.createBean(DataCenterUpdateAction.class, dc).reconcileDataCenter());
    }
     */
}
