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

    private AdmissionReview failedAdminssionReview(AdmissionReview admissionReview, Throwable t) {
        Status status = new Status();
        status.setCode(400);
        status.setMessage(t.getMessage());
        return new AdmissionReviewBuilder()
                .withApiVersion(admissionReview.getApiVersion())
                .withResponse(new AdmissionResponseBuilder()
                        .withAllowed(false)
                        .withStatus(status)
                        .withUid(admissionReview.getRequest().getUid()).build())
                .build();
    }

    /**
     * Use the fabric8 datacenter for webhook admission.
     *
     * @param admissionReview
     * @return
     * @throws ApiException
     */
    @Post(value = "/", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    public Single<AdmissionReview> validate(@QueryValue("timeout") Duration timeout,
                                            @Body AdmissionReview admissionReview) {
        logger.debug("Validating admissionReview={}", admissionReview);
        try {
            com.strapdata.strapkop.model.fabric8.datacenter.DataCenter datacenter = (com.strapdata.strapkop.model.fabric8.datacenter.DataCenter) admissionReview.getRequest().getObject();
            DataCenterSpec dataCenterSpec = datacenter.getSpec();
            Key dcKey = new Key(admissionReview.getRequest().getNamespace(), admissionReview.getRequest().getName());

            if (admissionReview.getRequest().getOldObject() == null) {
                try {
                    checkDatacenterSpecConsistency(dataCenterSpec);
                    logger.info("Accept dc={}", dcKey);
                    return Single.just(new AdmissionReviewBuilder()
                            .withApiVersion(admissionReview.getApiVersion())
                            .withResponse(new AdmissionResponseBuilder()
                                    .withAllowed(true)
                                    .withUid(admissionReview.getRequest().getUid()).build())
                            .build());
                } catch (Throwable t) {
                    logger.warn("Admission failed dc=" + dcKey + ":", t);
                    return Single.just(failedAdminssionReview(admissionReview, t));
                }
            }

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

                        // Attempt to change cassandra ssl storage ports
                        if (!deployedDc.getSpec().getCassandra().getSslStoragePort().equals(dataCenterSpec.getCassandra().getSslStoragePort())) {
                            throw new IllegalArgumentException("Cannot change the cassandra.sslStoragePort");
                        }
                        // Attempt to change cassandra storage ports
                        if (!deployedDc.getSpec().getCassandra().getStoragePort().equals(dataCenterSpec.getCassandra().getStoragePort())) {
                            throw new IllegalArgumentException("Cannot change the cassandra.storagePort");
                        }
                        // Attempt to change elasticsearch transport port
                        if (!deployedDc.getSpec().getElasticsearch().getTransportPort().equals(dataCenterSpec.getElasticsearch().getTransportPort())) {
                            throw new IllegalArgumentException("Cannot change the elasticsearch.transportPort");
                        }

                        // check dc spec consistency
                        checkDatacenterSpecConsistency(dataCenterSpec);

                        logger.info("Accept dc={}", dcKey);
                        return new AdmissionReviewBuilder()
                                .withApiVersion(admissionReview.getApiVersion())
                                .withResponse(new AdmissionResponseBuilder()
                                        .withAllowed(true)
                                        .withUid(admissionReview.getRequest().getUid()).build())
                                .build();
                    })
                    .onErrorReturn(t -> {
                        logger.warn("Admission failed dc=" + dcKey + ":", t);
                        return failedAdminssionReview(admissionReview, t);
                    });
        } catch(Throwable t) {
            logger.warn("Admission failed dc=" + admissionReview.getRequest().getNamespace() +"/" + admissionReview.getRequest().getName() + ":", t);
            return Single.just(failedAdminssionReview(admissionReview, t));
        }
    }
}