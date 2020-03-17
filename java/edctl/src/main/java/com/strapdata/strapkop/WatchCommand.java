package com.strapdata.strapkop;

import com.google.common.reflect.TypeToken;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.strapkop.model.k8s.cassandra.Health;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
import picocli.CommandLine;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@CommandLine.Command(name = "watch", description = "edctl watch subcommand")
public class WatchCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"-n","--namespace"}, description = "Kubernetes namespace", defaultValue = "default")
    String namespace;

    @CommandLine.Option(names = {"-p","--phase"}, description = "Elassandra datacenter phase")
    DataCenterPhase phase;

    @CommandLine.Option(names = {"--health"}, description = "Elassandra datacenter health")
    Health health;

    @CommandLine.Option(names = {"-r","--replicas"}, description = "Elassandra datacenter ready replicas")
    Integer readyReplicas;

    @CommandLine.Option(names = {"-t","--timeout"}, description = "Wait timeout", defaultValue = "600")
    Integer timeout;

    @Override
    public Integer call() throws Exception {
        System.out.println("Waiting phase="+phase+" health=" + health + " replicas="+readyReplicas+" timeout="+timeout+"s");

        ApiClient client = Config.defaultClient();
        client.getHttpClient().setReadTimeout(timeout, TimeUnit.SECONDS);
        Configuration.setDefaultApiClient(client);
        CustomObjectsApi customObjectsApi = new CustomObjectsApi(client);

        Watch<DataCenter> watch = Watch.createWatch(client,
                customObjectsApi.listNamespacedCustomObjectCall(StrapdataCrdGroup.GROUP, DataCenter.VERSION,
                        namespace, DataCenter.PLURAL, null, null, null,
                        null, timeout, true, null, null),
                new TypeToken<Watch.Response<DataCenter>>(){}.getType());

        long start = System.currentTimeMillis();
        for (Watch.Response<DataCenter> item : watch) {
            System.out.printf("%s : %s phase=%s heath=%s replicas=%d %n", item.type, item.object.getMetadata().getName(),
                    item.object.getStatus().getPhase().name(), item.object.getStatus().getHealth().name(), item.object.getStatus().getReadyReplicas());
            boolean conditionMet = true;

            if (phase != null && !phase.equals(item.object.getStatus().getPhase()))
                conditionMet = false;

            if (health != null && !health.equals(item.object.getStatus().getHealth()))
                conditionMet = false;

            if (readyReplicas != null && ! readyReplicas.equals(item.object.getStatus().getReadyReplicas()))
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
