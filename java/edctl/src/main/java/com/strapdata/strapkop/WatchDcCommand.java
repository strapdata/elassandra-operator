package com.strapdata.strapkop;

import com.google.common.reflect.TypeToken;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.strapkop.model.k8s.cassandra.Health;
import com.strapdata.strapkop.model.k8s.cassandra.ReaperPhase;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "watch-dc", description = "edctl watch datacenter subcommand")
public class WatchDcCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"-n","--namespace"}, description = "Kubernetes namespace", defaultValue = "default")
    String namespace;

    @CommandLine.Option(names = {"-p","--phase"}, description = "Elassandra datacenter phase")
    DataCenterPhase phase;

    @CommandLine.Option(names = {"--health"}, description = "Elassandra datacenter health")
    Health health;

    @CommandLine.Option(names = {"-r","--replicas"}, description = "Elassandra datacenter ready replicas")
    Integer readyReplicas;

    @CommandLine.Option(names = {"--reaper"}, description = "Elassandra reaper phase")
    ReaperPhase reaperPhase;

    @CommandLine.Option(names = {"-t","--timeout"}, description = "Wait timeout", defaultValue = "600")
    Integer timeout;

    @CommandLine.Option(names = {"-v","--verbose"}, description = "Verbose mode")
    Boolean verbose;

    @Override
    public Integer call() throws Exception {
        System.out.println("Waiting datacenter namespace="+namespace+
                " phase=" + phase +
                " health=" + health +
                " replicas=" + readyReplicas +
                " reaper=" + reaperPhase +
                " timeout="+timeout+"s");

        ApiClient client = Config.defaultClient().setReadTimeout(timeout * 1000);
        Configuration.setDefaultApiClient(client);
        CustomObjectsApi customObjectsApi = new CustomObjectsApi(client);

        Watch<DataCenter> watch = Watch.createWatch(client,
                customObjectsApi.listNamespacedCustomObjectCall(StrapdataCrdGroup.GROUP, DataCenter.VERSION,
                        namespace, DataCenter.PLURAL, null, null, null,
                        null, timeout, null, null, true, null),
                new TypeToken<Watch.Response<DataCenter>>(){}.getType());

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

            if (readyReplicas != null && ! readyReplicas.equals(item.object.getStatus().getReadyReplicas()))
                conditionMet = false;

            if (reaperPhase != null && !reaperPhase.equals(item.object.getStatus().getReaperPhase()))
                conditionMet = false;

            if (conditionMet) {
                long end = System.currentTimeMillis();
                System.out.println("done " + (end-start) + "ms");
                return 0;
            }
        }
        long end = System.currentTimeMillis();
        System.out.println("timeout " + (end-start) + "ms");
        return 1;
    }
}
