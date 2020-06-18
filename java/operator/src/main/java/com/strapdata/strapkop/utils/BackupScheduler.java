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

package com.strapdata.strapkop.utils;

import com.google.common.base.Strings;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.ScheduledBackup;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.cache.Cache;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import io.kubernetes.client.openapi.ApiException;
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
