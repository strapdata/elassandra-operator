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

import com.strapdata.strapkop.cache.StatefulsetCache;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeAddress;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.reactivex.Single;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;

/**
 * Return seed nodes IP (pod 0 for active racks)
 */
@Tag(name = "seeds")
@Controller("/seeds")
public class SeedsController {

    private final Logger logger = LoggerFactory.getLogger(SeedsController.class);

    @Inject
    SharedInformerFactory sharedInformerFactory;

    @Inject
    StatefulsetCache statefulsetCache;


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
     * @return
     */
    @Get(value = "/{namespace}/{clusterName}/{datacenterName}", produces = MediaType.APPLICATION_JSON)
    public Single<List<String>> seeds(@QueryValue("namespace") String namespace,
                                      @QueryValue("clusterName") String clusterName,
                                      @QueryValue("datacenterName") String datacenterName) throws ApiException {
        Key dcKey = new Key(namespace, OperatorNames.dataCenterResource(clusterName, datacenterName));
        DataCenter dataCenter = sharedInformerFactory.getExistingSharedIndexInformer(DataCenter.class).getIndexer().getByKey(dcKey.id());
        if (dataCenter == null)
            throw new IllegalArgumentException("Datacenter not found");

        TreeMap<String, V1StatefulSet> stsMap = statefulsetCache.get(dcKey);
        if (stsMap == null)
            throw new IllegalArgumentException("No StatefulSet found");

        List<String> seeds = new ArrayList<>();
        Map<String, String> hostIpToExternalIp = new HashMap<>();
        for(V1Node node : sharedInformerFactory.getExistingSharedIndexInformer(V1Node.class).getIndexer().list()) {
            String internalIp = null;
            String externalIp = null;
            if (node.getStatus() != null && node.getStatus().getAddresses() != null) {
                for(V1NodeAddress v1NodeAddress : node.getStatus().getAddresses()) {
                    if (v1NodeAddress.getType().equals("InternalIP")) {
                        internalIp = v1NodeAddress.getAddress();
                    }
                    if (v1NodeAddress.getType().equals("ExternalIP")) {
                        externalIp = v1NodeAddress.getAddress();
                    }
                }
            }
            String publicIp = node.getMetadata().getAnnotations().get("elassandra.strapdata.com/public-ip");
            if (publicIp != null)
                externalIp = publicIp;

            if (internalIp != null)
                hostIpToExternalIp.put(internalIp, (externalIp == null) ? internalIp : externalIp);
        }

        for(V1StatefulSet statefulSet : stsMap.values()) {
            if (statefulSet.getStatus() != null && statefulSet.getStatus().getCurrentReplicas() != null && statefulSet.getStatus().getCurrentReplicas() > 0) {
                String podName = OperatorNames.podName(dataCenter, Integer.parseInt(statefulSet.getMetadata().getLabels().get(OperatorLabels.RACKINDEX)), 0);
                V1Pod pod = sharedInformerFactory.getExistingSharedIndexInformer(V1Pod.class).getIndexer().getByKey(namespace + "/" + podName);
                if (pod != null && pod.getStatus() != null && pod.getStatus().getHostIP() != null) {
                    String hostIp = pod.getStatus().getHostIP();
                    String externalIp = hostIpToExternalIp.get(hostIp);
                    if (dataCenter.getSpec().getNetworking().getHostNetworkEnabled() || dataCenter.getSpec().getNetworking().getHostPortEnabled()) {
                        seeds.add(externalIp == null ? hostIp : externalIp);
                    } else {
                        seeds.add(pod.getStatus().getPodIP());
                    }
                }
            }
        }

        logger.info("datacenter={} seeds={}", dataCenter.id(), seeds);
        return Single.just(seeds);
    }

    @Error
    @SuppressWarnings("rawtypes")
    public HttpResponse<String> handleError(HttpRequest request, Throwable e) {
        if (e instanceof IllegalArgumentException)
            return HttpResponse.<String>status(HttpStatus.NOT_FOUND, e.getMessage()).body(e.getMessage());
        return HttpResponse.<String>status(HttpStatus.BAD_REQUEST, e.getMessage()).body(e.getMessage());
    }
}
