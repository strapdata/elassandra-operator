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

package com.strapdata.strapkop.preflight;

import com.strapdata.strapkop.k8s.K8sController;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.ApiextensionsV1Api;
import io.kubernetes.client.openapi.models.V1CustomResourceDefinition;
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

    @Inject
    ApiextensionsV1Api apiExtensionsApi;

    @Inject
    K8sController kubernetesController;

    @Override
    public Void call() throws Exception {
        createCrdFromResource("/datacenter-crd.yaml");
        createCrdFromResource("/task-crd.yaml");
        kubernetesController.start();   // start when CRD are deployed
        return null;
    }

    private void createCrdFromResource(final String resourceName) throws ApiException, IOException {
        try (final InputStream resourceStream = StrapdataCrdGroup.class.getResourceAsStream(resourceName);
             final InputStreamReader resourceReader = new InputStreamReader(resourceStream);) {

            final V1CustomResourceDefinition crdDefinition = Yaml.loadAs(resourceReader, V1CustomResourceDefinition.class);
            final String crdName = crdDefinition.getMetadata().getName();

            try {
                apiExtensionsApi.createCustomResourceDefinition(crdDefinition, null, null, null);
                logger.info("Custom Resource Definition {} created", crdName);
            } catch (final ApiException e) {
                if (e.getCode() == 409) { // HTTP 409 CONFLICT
                    logger.info("Custom Resource Definition {} already exists.", crdName);
                    return;
                }
                logger.warn("error code=" + e.getCode() + " body=" + e.getResponseBody(), e);
                throw e;
            }
        }
    }
}
