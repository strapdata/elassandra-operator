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

package com.strapdata.strapkop;

import com.google.common.reflect.TypeToken;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.KubeConfig;
import io.kubernetes.client.util.Watch;
import picocli.CommandLine;

import java.io.FileReader;
import java.util.Date;
import java.util.concurrent.Callable;

    @CommandLine.Command(name = "watch-task", description = "Watch elassandra task subcommand")
    public class WatchTaskCommand implements Callable<Integer> {

        @CommandLine.Option(names = {"--context"}, description = "Kubernetes context")
        String context;

        @CommandLine.Option(names = {"-n", "--name"}, description = "Elassandra task name")
        String name;

        @CommandLine.Option(names = {"-ns", "--namespace"}, description = "Kubernetes namespace", defaultValue = "default")
        String namespace;

        @CommandLine.Option(names = {"-p", "--phase"}, description = "Elassandra task phase")
        TaskPhase phase;

        @CommandLine.Option(names = {"-t", "--timeout"}, description = "Wait timeout in second", defaultValue = "600")
        Integer timeout;

        @CommandLine.Option(names = {"-v", "--verbose"}, description = "Verbose mode")
        Boolean verbose;

        @Override
        public Integer call() throws Exception {
            System.out.println("Watching elassandra task" +
                    (context == null ? "" : " context=" + context) +
                    (name == null ? "" : " name=" + name) +
                    (namespace == null ? "" : " namespace=" + namespace) +
                    (phase == null ? "" : " phase=" + phase) +
                    (timeout == null ? "" : " timeout=" + timeout + "s"));

            String kubeConfigPath = "~/.kube/config";
            if (System.getenv("KUBE_CONFIG") != null)
                kubeConfigPath = System.getenv("KUBE_CONFIG");

            KubeConfig kubeConfig = KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath.replaceFirst("^~", System.getProperty("user.home"))));
            if (context != null)
                kubeConfig.setContext(context);

            ApiClient client = Config.fromConfig(kubeConfig).setReadTimeout(timeout * 1000);
            Configuration.setDefaultApiClient(client);
            CustomObjectsApi customObjectsApi = new CustomObjectsApi(client);

            String fieldSelector = name == null ? null : "metadata.name=" + name;
            Watch<Task> watch = Watch.createWatch(client,
                    customObjectsApi.listNamespacedCustomObjectCall(StrapdataCrdGroup.GROUP, Task.VERSION,
                            namespace, Task.PLURAL, null, null, fieldSelector,
                            null, null, null, timeout, true, null),
                    new TypeToken<Watch.Response<Task>>() {
                    }.getType());

            long start = System.currentTimeMillis();
            for (Watch.Response<Task> item : watch) {
                System.out.printf("\"%tT %s: %s phase=%s %n",
                        new Date(),
                        item.type,
                        item.object.getMetadata().getName(),
                        item.object.getStatus().getPhase().name());
                boolean conditionMet = true;

                if (Boolean.TRUE.equals(verbose))
                    System.out.println(item.object);

                if (phase != null && !phase.equals(item.object.getStatus().getPhase()))
                    conditionMet = false;

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
