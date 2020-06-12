package com.strapdata.strapkop;

import com.google.common.reflect.TypeToken;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterPhase;
import com.strapdata.strapkop.model.k8s.datacenter.Health;
import com.strapdata.strapkop.model.k8s.datacenter.ReaperPhase;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "watch-dc", description = "Watch elassandra datacenter subcommand")
public class WatchDcCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"-n", "--name"}, description = "Elassandra datacenter name")
    String name;

    @CommandLine.Option(names = {"-ns", "--namespace"}, description = "Kubernetes namespace", defaultValue = "default")
    String namespace;

    @CommandLine.Option(names = {"-p", "--phase"}, description = "Expected elassandra datacenter phase")
    DataCenterPhase phase;

    @CommandLine.Option(names = {"--health"}, description = "Expected elassandra datacenter health")
    Health health;

    @CommandLine.Option(names = {"-r", "--replicas"}, description = "Expected number of ready replicas")
    Integer readyReplicas;

    @CommandLine.Option(names = {"--min-replicas"}, description = "Minimum number of ready replicas")
    Integer minReadyReplicas = -1;

    @CommandLine.Option(names = {"--reaper"}, description = "Expected elassandra reaper phase")
    ReaperPhase reaperPhase;

    @CommandLine.Option(names = {"-t", "--timeout"}, description = "Wait timeout", defaultValue = "600")
    Integer timeout;

    @CommandLine.Option(names = {"-v", "--verbose"}, description = "Verbose mode")
    Boolean verbose;

    @Override
    public Integer call() throws Exception {
        System.out.println("Waiting elassandra datacenter " +
                (name == null ? "" : "name=" + name) +
                (namespace == null ? "" : " namespace=" + namespace) +
                (phase == null ? "" : " phase=" + phase) +
                (health == null ? "" : " health=" + health) +
                (readyReplicas == null ? "" : " replicas=" + readyReplicas) +
                (reaperPhase == null ? "" : " reaper=" + reaperPhase) +
                (timeout == null ? "" : " timeout=" + timeout + "s"));

        ApiClient client = Config.defaultClient().setReadTimeout(timeout * 1000);
        Configuration.setDefaultApiClient(client);
        CustomObjectsApi customObjectsApi = new CustomObjectsApi(client);

        String fieldSelector = name == null ? null : "metadata.name=" + name;
        Watch<DataCenter> watch = Watch.createWatch(client,
                customObjectsApi.listNamespacedCustomObjectCall(StrapdataCrdGroup.GROUP, DataCenter.VERSION,
                        namespace, DataCenter.PLURAL, null, null, fieldSelector,
                        null, null, null, timeout, true, null),
                new TypeToken<Watch.Response<DataCenter>>() {
                }.getType());

        long start = System.currentTimeMillis();
        for (Watch.Response<DataCenter> item : watch) {
            System.out.printf("%s : %s phase=%s heath=%s replicas=%d reaper=%s \n", item.type, item.object.getMetadata().getName(),
                    item.object.getStatus().getPhase().name(),
                    item.object.getStatus().getHealth().name(),
                    item.object.getStatus().getReadyReplicas(),
                    item.object.getStatus().getReaperPhase());
            boolean conditionMet = true;

            if (Boolean.TRUE.equals(verbose))
                System.out.println(item.object);

            if (phase != null && !phase.equals(item.object.getStatus().getPhase()))
                conditionMet = false;

            if (health != null && !health.equals(item.object.getStatus().getHealth()))
                conditionMet = false;

            if (readyReplicas != null && !readyReplicas.equals(item.object.getStatus().getReadyReplicas()))
                conditionMet = false;

            if (reaperPhase != null && !reaperPhase.equals(item.object.getStatus().getReaperPhase()))
                conditionMet = false;

            // error if minAvailableReplicas condition not met while watching a rolling restart
            if (minReadyReplicas >= 0 && item.object.getStatus().getReadyReplicas() < minReadyReplicas) {
                System.out.println("Error: readyReplicas=" + item.object.getStatus().getReadyReplicas() + " < minReadyReplicas=" + minReadyReplicas);
                return 1;
            }

            if (conditionMet) {
                long end = System.currentTimeMillis();
                System.out.println("done " + (end - start) + "ms");
                return 0;
            }
        }
        long end = System.currentTimeMillis();
        System.out.println("timeout " + (end - start) + "ms");
        return 1;
    }
}
