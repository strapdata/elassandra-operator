package com.strapdata.strapkop.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.strapdata.strapkop.cache.CheckPointCache;
import com.strapdata.strapkop.cql.CqlKeyspace;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRole;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.pipeline.WorkQueues;
import io.fabric8.kubernetes.api.model.admission.AdmissionResponseBuilder;
import io.fabric8.kubernetes.api.model.admission.AdmissionReview;
import io.fabric8.kubernetes.api.model.admission.AdmissionReviewBuilder;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import io.kubernetes.client.openapi.ApiException;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.Map;

@Controller("/datacenter")
public class DatacenterController {

    private final Logger logger = LoggerFactory.getLogger(DatacenterController.class);

    @Inject
    WorkQueues workQueue;

    @Inject
    CheckPointCache checkPointCache;

    @Inject
    com.strapdata.strapkop.reconcilier.DataCenterController dataCenterController;

    @Inject
    CqlKeyspaceManager cqlKeyspaceManager;

    @Inject
    CqlRoleManager cqlRoleManager;

    @Inject
    ObjectMapper mapper;

    /*
    @Post(value = "/{namespace}/{cluster}/{datacenter}/rollback", produces = MediaType.APPLICATION_JSON)
    public HttpStatus rollback(String namespace, String cluster, String datacenter) throws ApiException {
        Key dcKey = new Key(OperatorNames.dataCenterResource(cluster, datacenter), namespace);
        if (checkPointCache.containsKey(dcKey)) {
            ClusterKey clusterKey = new ClusterKey(cluster, namespace);
            logger.info("Summit a configuration rollback for namespace={} cluster={} dc={}", namespace, cluster, datacenter);
            //workQueue.submit(clusterKey, dataCenterRollbackReconcilier.reconcile(dcKey));
            return HttpStatus.ACCEPTED;
        } else {
            logger.info("No restore point for namespace={} cluster={} dc={}", namespace, cluster, datacenter);
            return HttpStatus.NO_CONTENT;
        }
    }

    @Post(value = "/{namespace}/{cluster}/{datacenter}/reconcile", produces = MediaType.APPLICATION_JSON)
    public HttpStatus reconcile(String namespace, String cluster, String datacenter) throws ApiException {
        ClusterKey clusterKey = new ClusterKey(cluster, namespace);
        Key dcKey = new Key(OperatorNames.dataCenterResource(cluster, datacenter), namespace);
        logger.info("Force a configuration reconciliation for namespace={} cluster={} dc={}", namespace, cluster, datacenter);
        checkPointCache.remove(dcKey); // clear the restorePoint to take the current value of DC CRD
        workQueue.submit(clusterKey, dataCenterController.reconcile(namespace, cluster, datacenter));
        return HttpStatus.ACCEPTED;
    }
    */

    @PostConstruct
    public void initCrds() {
        KubernetesDeserializer.registerCustomKind(StrapdataCrdGroup.GROUP + "/" + Task.VERSION, Task.KIND, com.strapdata.strapkop.model.fabric8.task.Task.class);
        KubernetesDeserializer.registerCustomKind(StrapdataCrdGroup.GROUP + "/" + DataCenter.VERSION, DataCenter.KIND, com.strapdata.strapkop.model.fabric8.datacenter.DataCenter.class);

        SimpleModule module = new SimpleModule();
        module.addDeserializer(Object.class, new KubernetesDeserializer());
        mapper.registerModule(module);
    }

    @Get(value = "/{namespace}/{cluster}/{datacenter}/_keyspace", produces = MediaType.APPLICATION_JSON)
    public Map<String, CqlKeyspace> managedKeyspaces(String namespace, String cluster, String datacenter) throws ApiException {
        ClusterKey clusterKey = new ClusterKey(cluster, namespace);
        Key dcKey = new Key(OperatorNames.dataCenterResource(cluster, datacenter), namespace);
        return cqlKeyspaceManager.get(namespace, cluster, datacenter);
    }

    @Get(value = "/{namespace}/{cluster}/{datacenter}/_role", produces = MediaType.APPLICATION_JSON)
    public Map<String, CqlRole> managedRoles(String namespace, String cluster, String datacenter) throws ApiException {
        ClusterKey clusterKey = new ClusterKey(cluster, namespace);
        Key dcKey = new Key(OperatorNames.dataCenterResource(cluster, datacenter), namespace);
        return cqlRoleManager.get(namespace, cluster, datacenter);
    }

    @Post(value = "/validation", consumes = MediaType.APPLICATION_JSON)
    public Single<AdmissionReview> validate(@Body AdmissionReview admissionReview) throws ApiException {
        //io.micronaut.jackson.codec.JsonMediaTypeCodec;
        logger.warn("input admissionReview={}", admissionReview);
        AdmissionReview admissionReview1 = new AdmissionReviewBuilder()
                .withResponse(new AdmissionResponseBuilder()
                        .withAllowed(true)
                        .withUid(admissionReview.getRequest().getUid()).build())
                .build();
        logger.warn("output admissionReview={}", admissionReview1);
        return Single.just(admissionReview1);
    }
}