package com.strapdata.strapkop.controllers;

import io.kubernetes.client.ApiException;
import io.kubernetes.client.proto.V1beta1Admission;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * K8s webhook validation controller
 */
@Controller("/validation")
public class ValidationController {

    private final Logger logger = LoggerFactory.getLogger(ValidationController.class);

    /**
     * Webhooks are sent a POST request, with Content-Type: application/json,
     * with an AdmissionReview API object in the admission.k8s.io API group serialized to JSON as the body.
     * @return
     * @throws ApiException
     */
    @Post(value = "/", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON )
    public Single<V1beta1Admission.AdmissionResponse> validate(V1beta1Admission.AdmissionRequest admissionRequest) throws ApiException {
        logger.warn("Validate request={}", admissionRequest);
        V1beta1Admission.AdmissionResponse.Builder builder = V1beta1Admission.AdmissionResponse.newBuilder();
        builder.setAllowed(true);
        builder.setUid(admissionRequest.getUid());
        return Single.just(builder.build());

    }
}
