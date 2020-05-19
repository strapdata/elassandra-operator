package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import io.kubernetes.client.ApiException;
import io.micronaut.discovery.event.ServiceShutdownEvent;
import io.micronaut.discovery.event.ServiceStartedEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.scheduling.annotation.Async;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Timer;
import java.util.TimerTask;

@Singleton
public class TasksCleaner {
    private static final Logger logger = LoggerFactory.getLogger(TasksCleaner.class);
    private Timer cleanerThread;

    @Inject
    private K8sResourceUtils k8sResourceUtils;

    @Inject
    private OperatorConfig operatorConfig;

    @EventListener
    @Async
    void onStartup(ServiceStartedEvent event) {
        this.cleanerThread = new Timer("elassandra-tasks-cleaner", true);
        final int retentionInMs = (int) operatorConfig.getTaskRetention().getSeconds() * 1000;
        // start cleaner thread after 60s and execute it every retentionInMs/5
        cleanerThread.schedule(new Cleaner(retentionInMs, operatorConfig.getNamespace()), 60_000l, retentionInMs / 5);
    }

    @EventListener
    @Async
    void onShutdown(ServiceShutdownEvent event) {
        if (cleanerThread != null){
            cleanerThread.cancel();
        }
    }

    private class Cleaner extends TimerTask {
        private final int retentionInMs;
        private final String namespace;

        public Cleaner(int retentionInMs, String namespace) {
            this.retentionInMs = retentionInMs;
            this.namespace = namespace;
        }

        @Override
        public void run() {
            try {
                Iterable<Task> tasks = k8sResourceUtils.listNamespacedTask(namespace, null);
                tasks.forEach((task) -> {
                    if (task.getMetadata().getCreationTimestamp().plusMillis(retentionInMs).isBeforeNow()) {
                        logger.debug("Clearing task '{}' older than {} ms", task.getMetadata().getName(), retentionInMs);
                        // trigger the deletion but not wait the end.
                        try {
                            k8sResourceUtils.deleteTask(task.getMetadata()).subscribe();
                        } catch (ApiException ae) {
                            logger.info("cleaner iteration fails on task '{}' due to : {}", task.getMetadata().getName(), ae.getMessage(), ae);
                        }
                    }
                });
            } catch (Exception e) {
                logger.info("cleaner iteration fails due to : {}", e.getMessage(), e);
            }
        }
    }
}
