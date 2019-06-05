package com.strapdata.strapkop.preflight;

import com.strapdata.strapkop.k8s.K8sModule;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.micronaut.discovery.event.ServiceStartedEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.scheduling.annotation.Async;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Creates CRD defintion and defaultCA
 */
@Singleton
public class PreflightService {

    static final Logger logger = LoggerFactory.getLogger(PreflightService.class);

    @Inject
    K8sModule k8sModule;

    @Inject
    AuthorityManager authorityManager;

    public PreflightService() {
    }
    
    @EventListener
    @Async
    void onStartup(ServiceStartedEvent event) {
        try {
            CreateCustomResourceDefinitions CreateCustomResourceDefinitions = new CreateCustomResourceDefinitions(this.k8sModule.providesApiExtensionsV1beta1Api());
            CreateCustomResourceDefinitions.call();
        } catch (Exception e) {
            logger.error("Unexpected error:", e);
        }

        try {
            GenerateDefaultCA generateDefaultCA = new GenerateDefaultCA(authorityManager);
            generateDefaultCA.call();
        } catch (Exception e) {
            logger.error("Unexpected error:", e);
        }
    }

}
