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

package com.strapdata.strapkop.controllers;

import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterSpec;
import com.strapdata.strapkop.model.k8s.datacenter.PodsAffinityPolicy;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.api.model.admission.AdmissionResponseBuilder;
import io.fabric8.kubernetes.api.model.admission.AdmissionReview;
import io.fabric8.kubernetes.api.model.admission.AdmissionReviewBuilder;
import io.kubernetes.client.openapi.ApiException;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.time.Duration;

/**
 * Kubernetes webhook validation controller
 */
@Controller("/validation")
public class ValidationController {

    private final Logger logger = LoggerFactory.getLogger(ValidationController.class);

    @Inject
    K8sResourceUtils k8sResourceUtils;

    private void checkDatacenterSpecConsistency(DataCenterSpec dataCenterSpec) {
        // Check pod affinity
        if (PodsAffinityPolicy.SLACK.equals(dataCenterSpec.getPodsAffinityPolicy()) &&
                (dataCenterSpec.getNetworking().getHostNetworkEnabled() ||
                        dataCenterSpec.getNetworking().getHostPortEnabled())) {
            throw new IllegalArgumentException("PodsAffinityPolicy cannot be SLACK when hostNetwork or hostPort is true, this would cause a TCP port conflict.");
        }

        // check externalDns
        if (dataCenterSpec.getNetworking() != null && dataCenterSpec.getNetworking().getExternalDns() != null) {
            dataCenterSpec.getNetworking().getExternalDns().validate();
        }
    }



    /**
     * Use the fabric8 datacenter for webhook admission.
     * @param admissionReview
     * @return
     * @throws ApiException
     */
    @Post(value = "/", consumes = MediaType.APPLICATION_JSON)
    public Single<AdmissionReview> validate(@QueryValue("timeout") Duration timeout,
                                            @Body AdmissionReview admissionReview) throws ApiException {
        logger.warn("input admissionReview={}", admissionReview);
        com.strapdata.strapkop.model.fabric8.datacenter.DataCenter datacenter = (com.strapdata.strapkop.model.fabric8.datacenter.DataCenter) admissionReview.getRequest().getObject();
        DataCenterSpec dataCenterSpec = datacenter.getSpec();

        if (admissionReview.getRequest().getName() == null) {
            checkDatacenterSpecConsistency(dataCenterSpec);
            // resource created.
            return Single.just(new AdmissionReviewBuilder()
                    .withResponse(new AdmissionResponseBuilder()
                            .withAllowed(true)
                            .withUid(admissionReview.getRequest().getUid()).build())
                    .build());
        }

        Key dcKey = new Key(admissionReview.getRequest().getName(), admissionReview.getRequest().getNamespace());
        return k8sResourceUtils.readDatacenter(dcKey)
                .map(deployedDc -> {
                    // Attempt to change the clusterName
                    if (!deployedDc.getSpec().getClusterName().equals(dataCenterSpec.getClusterName())) {
                        throw new IllegalArgumentException("Cannot change the cassandra cluster name");
                    }

                    // Attempt to change the datacenterName
                    if (!deployedDc.getSpec().getDatacenterName().equals(dataCenterSpec.getDatacenterName())) {
                        throw new IllegalArgumentException("Cannot change the cassandra datacenter name");
                    }

                    // Attempt to change the storageClassName
                    if (!deployedDc.getSpec().getDataVolumeClaim().getStorageClassName().equals(dataCenterSpec.getDataVolumeClaim().getStorageClassName())) {
                        throw new IllegalArgumentException("Cannot change the storageClassName");
                    }

                    // check dc spec consistency
                    checkDatacenterSpecConsistency(dataCenterSpec);

                    logger.debug("Accept datacenter={}", datacenter);
                    return new AdmissionReviewBuilder()
                            .withResponse(new AdmissionResponseBuilder()
                                    .withAllowed(true)
                                    .withUid(admissionReview.getRequest().getUid()).build())
                            .build();
                })
                .onErrorReturn(t -> {
                    logger.warn("Invalid datacenter key=" + dcKey, t);
                    Status status = new Status();
                    status.setCode(400);
                    status.setMessage(t.getMessage());
                    return new AdmissionReviewBuilder()
                            .withResponse(new AdmissionResponseBuilder()
                                    .withAllowed(false)
                                    .withStatus(status)
                                    .withUid(admissionReview.getRequest().getUid()).build())
                            .build();
                });
    }
}