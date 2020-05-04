package com.strapdata.strapkop.preflight;

import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.ApiextensionsV1beta1Api;
import io.kubernetes.client.models.V1beta1CustomResourceDefinition;
import io.kubernetes.client.util.Yaml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Deploy CRDs (Helm does not manage properly installation when CRD already exists)
 */
public class CreateCustomResourceDefinitions implements Preflight<Void> {

    static final Logger logger = LoggerFactory.getLogger(CreateCustomResourceDefinitions.class);

    private final ApiextensionsV1beta1Api apiExtensionsApi;

    @Inject
    public CreateCustomResourceDefinitions(final ApiextensionsV1beta1Api apiExtensionsApi) {
        this.apiExtensionsApi = apiExtensionsApi;
    }

    @Override
    public Void call() throws Exception {
        createCrdFromResource("/datacenter-crd.yaml");
        createCrdFromResource("/task-crd.yaml");
        return null;
    }

    private void createCrdFromResource(final String resourceName) throws ApiException, IOException {
        try (final InputStream resourceStream = StrapdataCrdGroup.class.getResourceAsStream(resourceName);
             final InputStreamReader resourceReader = new InputStreamReader(resourceStream);) {

            final V1beta1CustomResourceDefinition crdDefinition = Yaml.loadAs(resourceReader, V1beta1CustomResourceDefinition.class);
            final String crdName = crdDefinition.getMetadata().getName();

            try {
                apiExtensionsApi.createCustomResourceDefinition(crdDefinition, null, null, null);
                logger.info("Custom Resource Definition {} created", crdName);
            } catch (final ApiException e) {
                if (e.getCode() == 409) { // HTTP 409 CONFLICT
                    logger.info("Custom Resource Definition {} already exists.", crdName);
                    return;
                }
                throw e;
            }
        }
    }
}
