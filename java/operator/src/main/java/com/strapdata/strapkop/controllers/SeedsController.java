package com.strapdata.strapkop.controllers;

import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.reactivex.Single;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Return nodes IP of pod 0 for active racks
 */
@Tag(name = "seeds")
@Controller("/seeds")
public class SeedsController {

    private final Logger logger = LoggerFactory.getLogger(SeedsController.class);

    private final K8sResourceUtils k8sResourceUtils;
    private final CoreV1Api coreApi;

    public SeedsController(CoreV1Api coreApi, K8sResourceUtils k8sResourceUtils) {
        this.coreApi= coreApi;
        this.k8sResourceUtils = k8sResourceUtils;
    }

    /**
     * @return OK when preflight service are applied (CA and CRD installation)
     */
    @Get("/")
    public HttpStatus index() {
        return HttpStatus.OK;
    }

    /**
     * Retreive seed node (or pod) IP addresses or external DNS names.
     * @param namespace
     * @param clusterName
     * @param datacenterName
     * @param externalDns
     * @param remoteSeeder
     * @return
     */
    @Post(value = "/{namespace}/{clusterName}/{datacenterName}", consumes = MediaType.TEXT_PLAIN, produces = MediaType.APPLICATION_JSON)
    public Single<List<String>> seeds(@QueryValue("namespace") String namespace,
                                      @QueryValue("clusterName") String clusterName,
                                      @QueryValue("datacenterName") String datacenterName,
                                      @QueryValue(value = "externalDns",defaultValue = "false") Boolean externalDns,
                                      @Body String remoteSeeder) throws ApiException {
        return k8sResourceUtils.readDatacenter(new Key(OperatorNames.dataCenterResource(clusterName, datacenterName), namespace))
                .map(dataCenter -> {
                List<String> seeds = new ArrayList<>();

                k8sResourceUtils.listNamespacedStatefulSets(namespace, null, OperatorLabels.toSelector(OperatorLabels.datacenter(dataCenter)))
                        .forEach(statefulSet -> {
                                if (statefulSet != null && statefulSet.getStatus() != null && statefulSet.getStatus().getCurrentReplicas() != null && statefulSet.getStatus().getCurrentReplicas() > 0) {
                                    String podName = OperatorNames.podName(dataCenter, Integer.parseInt(statefulSet.getMetadata().getLabels().get(OperatorLabels.RACKINDEX)), 0);
                                    // retreive pod node IP
                                    final String labelSelector = OperatorLabels.toSelector(ImmutableMap.of(OperatorLabels.POD, podName));
                                    try {
                                        k8sResourceUtils.listNamespacedPods(namespace, null, labelSelector).forEach(pod -> {
                                            String nodeName = pod.getSpec().getNodeName();
                                            logger.debug("found node={}", nodeName);
                                            if (pod.getStatus() != null && pod.getStatus().getHostIP() != null) {
                                                if (dataCenter.getSpec().getNetworking().getHostPortEnabled() == true ||
                                                        dataCenter.getSpec().getNetworking().getHostNetworkEnabled() == true) {
                                                    if (externalDns && dataCenter.getSpec().getExternalDns() != null && dataCenter.getSpec().getExternalDns().getEnabled()) {
                                                        String seedHostname =
                                                                "elassandra-" + dataCenter.getMetadata().getNamespace()+
                                                                        "-" + dataCenter.getSpec().getClusterName().toLowerCase(Locale.ROOT) +
                                                                        "-" + dataCenter.getSpec().getDatacenterName().toLowerCase(Locale.ROOT) +
                                                                        "-" + statefulSet.getMetadata().getLabels().get(OperatorLabels.RACK).toLowerCase(Locale.ROOT) +
                                                                        "-0";
                                                        logger.debug("Add external hostname={}", seedHostname);
                                                    } else {
                                                        logger.debug("Add hostIp={}", pod.getStatus().getHostIP());
                                                        seeds.add(pod.getStatus().getHostIP());
                                                    }
                                                } else {
                                                    logger.debug("Add podIp={}", pod.getStatus().getPodIP());
                                                    seeds.add(pod.getStatus().getPodIP());
                                                }
                                            }
                                        });
                                    } catch (ApiException e) {
                                        logger.warn("Failed to get pod list", e);
                                    }
                                }
                        });
                logger.info("remoteSeeder={} seeds={}", remoteSeeder, seeds);
                return seeds;
        });
    }
}
