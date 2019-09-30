package com.strapdata.strapkop.plugins;

import com.strapdata.strapkop.cql.CqlConnectionManager;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateReconcilier;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.micronaut.context.ApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractPlugin implements Plugin {
    final static Logger logger = LoggerFactory.getLogger(DataCenterUpdateReconcilier.class);

    final ApplicationContext context;
    final K8sResourceUtils k8sResourceUtils;
    final AuthorityManager authorityManager;
    final CqlConnectionManager cqlConnectionManager;
    final CoreV1Api coreApi;
    final AppsV1Api appsApi;

    public AbstractPlugin(final ApplicationContext context,
                          K8sResourceUtils k8sResourceUtils,
                          AuthorityManager authorityManager,
                          CqlConnectionManager cqlConnectionManager,
                          CoreV1Api coreApi,
                          AppsV1Api appsApi) {
        this.context = context;
        this.k8sResourceUtils = k8sResourceUtils;
        this.authorityManager = authorityManager;
        this.cqlConnectionManager = cqlConnectionManager;
        this.coreApi = coreApi;
        this.appsApi = appsApi;
    }

}
