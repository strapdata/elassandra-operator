package com.strapdata.strapkop;

import com.google.common.reflect.TypeToken;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterPhase;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
import picocli.CommandLine;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@CommandLine.Command(name = "watch", description = "edctl watch subcommand")
public class WatchCommand implements Callable<Object> {

    @CommandLine.Option(names = {"-n","--namespace"}, description = "Kubernetes namespace", defaultValue = "default")
    String namespace;

    @CommandLine.Option(names = {"-p","--phase"}, description = "Elassandra datacenter phase", defaultValue = "RUNNING")
    DataCenterPhase phase;

    @CommandLine.Option(names = {"-t","--timeout"}, description = "Wait timeout", defaultValue = "600")
    Integer timeout;

    @Override
    public Object call() throws Exception {
        if (phase != null) {
            System.out.println("Waiting phase=" + phase + " timeout="+timeout+"s");

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
                System.out.printf("%s : %s phase=%s %n", item.type, item.object.getMetadata().getName(), item.object.getStatus().getPhase());
                if (item.object.getStatus().getPhase().equals(phase)) {
                    long end = System.currentTimeMillis();
                    System.out.println("done " + (end-start) + "ms");
                    System.exit(0);
                }
            }
            long end = System.currentTimeMillis();
            System.out.println("timeout " + (end-start) + "ms");
            System.exit(1);
        }
        return 0;
    }


}
