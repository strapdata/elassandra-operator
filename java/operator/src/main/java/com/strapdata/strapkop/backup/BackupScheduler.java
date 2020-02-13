package com.strapdata.strapkop.backup;

import com.google.common.base.Strings;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.ScheduledBackup;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.cache.Cache;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import io.kubernetes.client.ApiException;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.scheduling.ScheduledExecutorTaskScheduler;
import io.micronaut.scheduling.TaskExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

@Singleton
public class BackupScheduler extends Cache<Key, List<ScheduledFuture<?>>>
{
    private static final Logger logger = LoggerFactory.getLogger(BackupScheduler.class);

    protected final ScheduledExecutorTaskScheduler taskScheduler;

    private final K8sResourceUtils k8sResourceUtils;

    public BackupScheduler(@Named(TaskExecutors.SCHEDULED) ScheduledExecutorTaskScheduler taskScheduler, K8sResourceUtils k8sUtils) {
        this.taskScheduler = taskScheduler;
        this.k8sResourceUtils = k8sUtils;
    }

    public void cancelBackups(Key key) {
        getOrDefault(key, new ArrayList<>()).forEach((ScheduledFuture<?> future) -> future.cancel(true));
        remove(key);
    }

    public void scheduleBackups(final DataCenter datacenter) {
        List<ScheduledBackup> scheduledBackups = datacenter.getSpec().getScheduledBackups();
        if (CollectionUtils.isNotEmpty(scheduledBackups)) {
            scheduledBackups.forEach(def -> submitBackupTask(datacenter, def));
        }
    }

    public void submitBackupTask(final DataCenter datacenter, final ScheduledBackup backupDefinition) {
        Key key = new Key(datacenter.getMetadata());
        logger.debug("Submit backup task definition '{}' for datacenter '{}'", backupDefinition, datacenter.getMetadata().getName());

        putIfAbsent(key, new ArrayList<ScheduledFuture<?>>());

        if (!Strings.isNullOrEmpty(backupDefinition.getCron())) {
            Runnable called = () -> {
                try {
                    final String name = backupDefinition.computeTaskName();
                    final Task task = Task.fromDataCenter(name, datacenter);
                    task.getSpec().setBackup(backupDefinition.getBackup());
                    logger.debug("Create backup task : {}", task);
                    k8sResourceUtils.createTask(task).blockingGet();
                } catch (ApiException e) {
                    logger.warn("Unable to trigger a backup '{}' ", backupDefinition);
                }
            };
            get(key).add(taskScheduler.schedule(backupDefinition.getCron(), called));
        } else {
            logger.warn("Unable to submit backup definition for datacaenter '{}' without cron attribute : '{}' ", datacenter.getMetadata().getName() ,backupDefinition);
        }
    }
}
