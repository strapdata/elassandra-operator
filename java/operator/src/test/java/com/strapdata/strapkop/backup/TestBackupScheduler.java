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

package com.strapdata.strapkop.backup;

import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterSpec;
import com.strapdata.strapkop.model.k8s.datacenter.ScheduledBackup;
import com.strapdata.strapkop.model.k8s.task.BackupTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import io.kubernetes.client.models.V1ObjectMeta;
import io.micronaut.scheduling.ScheduledExecutorTaskScheduler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.Mockito.*;

public class TestBackupScheduler extends BackupScheduler {
    private static final String clusterName = "cl1";
    private static final String datacenterName = "dc1";

    private static K8sResourceUtils k8sResourceUtils;
    private static ExecutorService executor;
    private static ScheduledExecutorTaskScheduler scheduler;

    public TestBackupScheduler() {
        super(scheduler, k8sResourceUtils);
    }

    @BeforeAll
    public static void prepareMock() {
        k8sResourceUtils = mock(K8sResourceUtils.class);
        executor = Executors.newSingleThreadScheduledExecutor();
        scheduler = new ScheduledExecutorTaskScheduler(executor);
    }

    @AfterAll
    public static void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    public void testBackupScheduling() throws Exception {
        DataCenter dc = createMinimalDatacenter();
        ScheduledBackup definition = new ScheduledBackup()
                .setTagSuffix("mock")
                .setCron("0/10 * * * * ?") // every ten seconds
                .setBackup(new BackupTaskSpec());
        submitBackupTask(dc, definition);
        Thread.sleep(11_000);
        verify(k8sResourceUtils, times(1)).createTask(any(Task.class));
        Thread.sleep(10_000);
        verify(k8sResourceUtils, times(2)).createTask(any(Task.class));
        cancelBackups(new Key(dc.getMetadata()));
        reset(k8sResourceUtils);
        Thread.sleep(20_000);
        verify(k8sResourceUtils, times(0)).createTask(any(Task.class));
    }

    private DataCenter createMinimalDatacenter() {
        V1ObjectMeta v1ObjectMeta = new V1ObjectMeta();
        v1ObjectMeta.setNamespace("default");
        v1ObjectMeta.setName("elassandra-"+clusterName+"-"+datacenterName);
        return new DataCenter()
                .setSpec(new DataCenterSpec()
                        .setClusterName(clusterName)
                        .setDatacenterName(datacenterName))
                .setMetadata(v1ObjectMeta);
    }
}
