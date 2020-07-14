package com.strapdata.strapkop.k8s;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.NodeCache;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.dnsendpoint.DnsEndpoint;
import com.strapdata.strapkop.model.k8s.dnsendpoint.DnsEndpointList;
import com.strapdata.strapkop.model.k8s.dnsendpoint.DnsEndpointSpec;
import com.strapdata.strapkop.model.k8s.dnsendpoint.Endpoint;
import com.strapdata.strapkop.preflight.Preflight;
import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1DeleteOptions;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeAddress;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.util.CallGeneratorParams;
import io.micronaut.context.annotation.Requires;
import io.reactivex.Completable;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.Callable;

@Singleton
@Requires(property="operator.nodeDnsZone")
public class NodeDnsUpdateService implements Preflight {

    private final Logger logger = LoggerFactory.getLogger(K8sController.class);

    @Inject
    K8sController k8sController;

    @Inject
    CustomObjectsApi customObjectsApi;

    @Inject
    OperatorConfig operatorConfig;

    @Inject
    NodeCache nodeCache;

    @Inject
    SharedInformerFactory sharedInformerFactory;

    @Override
    public Void call() throws Exception {
        if (!org.elasticsearch.common.Strings.isNullOrEmpty(operatorConfig.getNodeDnsZone())) {
            logger.info("Starting DNSEndpoint informer zone={}", operatorConfig.getNodeDnsZone());
            addDnsEndpointInformer();
            k8sController.nodeInformer.addEventHandlerWithResyncPeriod(new ResourceEventHandler<V1Node>() {
                                                             @Override
                                                             public void onAdd(V1Node node) {
                                                                 addNode(node);
                                                             }

                                                             @Override
                                                             public void onUpdate(V1Node oldObj, V1Node newObj) {
                                                             }

                                                             @Override
                                                             public void onDelete(V1Node node, boolean deletedFinalStateUnknown) {
                                                                 deleteNode(node);
                                                             }
                                                         },
                    60000);
        }
        return null;
    }

    @Override
    public int order() {
        return 200;
    }

    void addDnsEndpointInformer() {
        SharedIndexInformer<DnsEndpoint> dnsInformer = sharedInformerFactory.sharedIndexInformerFor(
                (CallGeneratorParams params) -> {
                    return customObjectsApi.listNamespacedCustomObjectCall(
                            DnsEndpoint.GROUP,
                            DnsEndpoint.VERSION,
                            operatorConfig.getWatchNamespace(),
                            DnsEndpoint.PLURAL,
                            null,
                            null,
                            null,
                            null,
                            null,
                            params.resourceVersion,
                            params.timeoutSeconds,
                            params.watch, null);
                },
                DnsEndpoint.class,
                DnsEndpointList.class,
                60000);
    }

    void addNode(V1Node node) {
        final NodeCache.Address address = getAddress(node);
        if (address != null) {
            nodeCache.compute(node.getMetadata().getName(), (k,v) -> {
                if (v == null) {
                    v = address;
                    if (!Strings.isNullOrEmpty(operatorConfig.getNodeDnsZone())) {
                        DnsEndpoint dnsEndpoint = sharedInformerFactory.getExistingSharedIndexInformer(DnsEndpoint.class).getIndexer().getByKey(operatorConfig.getOperatorNamespace() + "/" + k);
                        if (dnsEndpoint == null) {
                            try {
                                addDnsEndpoint(node, address).blockingGet();
                            } catch(ApiException e) {
                                logger.error("Failed to upsert dnsEndpoint code={} body={}", e.getCode(), e.getResponseBody());
                            }
                        }
                    }
                }
                return v;
            });
        }
    }

    void deleteNode(V1Node node) {
        final NodeCache.Address address = getAddress(node);
        if (address != null) {
            nodeCache.compute(node.getMetadata().getName(), (k,v) -> {
                if (v == null) {
                    v = address;
                    if (!Strings.isNullOrEmpty(operatorConfig.getNodeDnsZone())) {
                        DnsEndpoint dnsEndpoint = sharedInformerFactory.getExistingSharedIndexInformer(DnsEndpoint.class).getIndexer().getByKey(operatorConfig.getOperatorNamespace() + "/" + k);
                        if (dnsEndpoint != null)
                            deleteDnsEndpoint(node, address).blockingGet();
                    }
                }
                return v;
            });
        }
    }

    NodeCache.Address getAddress(V1Node node) {
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

        return (internalIp != null)
                ? new NodeCache.Address().withExternalIp(externalIp).withInternalIp(internalIp)
                : null;
    }

    Single<Object> addDnsEndpoint(V1Node node, NodeCache.Address address) throws ApiException {
        DnsEndpoint dnsEndpoint = new DnsEndpoint()
                .withMetadata(new V1ObjectMeta()
                        .name(node.getMetadata().getName())
                        .namespace(operatorConfig.getOperatorNamespace())
                        .labels(OperatorLabels.MANAGED)
                )
                .withSpec(new DnsEndpointSpec()
                        .withEndpoints(new Endpoint[] {
                                new Endpoint()
                                        .withDnsName(address.publicName() + "." + operatorConfig.getNodeDnsZone())
                                        .withRecordType("A")
                                        .withRecordTTL(operatorConfig.getNodeDnsTtl())
                                        .setTargets(new String[] { address.getInternalIp() })
                        })
                )
                .withStatus(null);
            return K8sResourceUtils.createOrReplaceResource(operatorConfig.getOperatorNamespace(), dnsEndpoint,
                    () -> customObjectsApi.createNamespacedCustomObject(DnsEndpoint.GROUP, DnsEndpoint.VERSION, operatorConfig.getOperatorNamespace(),
                            DnsEndpoint.PLURAL, dnsEndpoint, null, null, "elassandra-operator"),
                    () -> customObjectsApi.replaceNamespacedCustomObject(DnsEndpoint.GROUP, DnsEndpoint.VERSION, operatorConfig.getOperatorNamespace(),
                            DnsEndpoint.PLURAL, dnsEndpoint.getMetadata().getName(), new ReplaceDnsEndpointSpecOp(dnsEndpoint.getSpec()), null, "elassandra-operator"));
    }

    private static class ReplaceDnsEndpointSpecOp {
        @JsonProperty
        String op = "replace";
        @JsonProperty
        String path = "/spec";
        @JsonProperty
        DnsEndpointSpec value;

        ReplaceDnsEndpointSpecOp(DnsEndpointSpec value) {
            this.value = value;
        }
    }

    Completable deleteDnsEndpoint(V1Node node, NodeCache.Address address) {
        return Completable.fromCallable(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                try {
                    return customObjectsApi.deleteNamespacedCustomObject(DnsEndpoint.GROUP, DnsEndpoint.VERSION, operatorConfig.getOperatorNamespace(),
                            DnsEndpoint.PLURAL, node.getMetadata().getName(), null, null, null, null, new V1DeleteOptions());
                } catch(ApiException e) {
                    logger.warn("failed to create dnsendpoint, code={} body={}", e.getCode(), e.getResponseBody());
                    throw e;
                }
            }
        });

    }
}
