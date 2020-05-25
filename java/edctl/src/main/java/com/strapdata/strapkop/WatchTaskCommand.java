package com.strapdata.strapkop;

import com.google.common.reflect.TypeToken;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "watch-task", description = "edctl watch task subcommand")
public class WatchTaskCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"-n","--namespace"}, description = "Kubernetes namespace", defaultValue = "default")
    String namespace;

    @CommandLine.Option(names = {"-p","--phase"}, description = "Elassandra task phase")
    TaskPhase phase;

    @CommandLine.Option(names = {"-t","--timeout"}, description = "Wait timeout in second", defaultValue = "600")
    Integer timeout;

    @CommandLine.Option(names = {"-v","--verbose"}, description = "Verbose mode")
    Boolean verbose;

    @Override
    public Integer call() throws Exception {
        System.out.println("Waiting namespace="+namespace+" phase="+phase+" timeout="+timeout+"s");

        ApiClient client = Config.defaultClient().setReadTimeout(timeout * 1000);
        Configuration.setDefaultApiClient(client);
        CustomObjectsApi customObjectsApi = new CustomObjectsApi(client);

        Watch<Task> watch = Watch.createWatch(client,
                customObjectsApi.listNamespacedCustomObjectCall(StrapdataCrdGroup.GROUP, DataCenter.VERSION,
                        namespace, Task.PLURAL, null, null, null,
                        null, timeout, null, null, true, null),
                new TypeToken<Watch.Response<DataCenter>>(){}.getType());

        long start = System.currentTimeMillis();
        for (Watch.Response<Task> item : watch) {
            System.out.printf("%s : %s phase=%s %n", item.type, item.object.getMetadata().getName(),
                    item.object.getStatus().getPhase().name());
            boolean conditionMet = true;

            if (Boolean.TRUE.equals(verbose))
                System.out.println(item.object);

            if (phase != null && !phase.equals(item.object.getStatus().getPhase()))
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
