package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.plugins.TestSuitePlugin;
import io.micrometer.core.instrument.MeterRegistry;
import io.reactivex.Completable;

import javax.inject.Singleton;

@Singleton
public class TestTaskReconcilier extends TaskReconcilier {

    private final TestSuitePlugin testSuitePlugin;

    public TestTaskReconcilier(ReconcilierObserver reconcilierObserver,
                               final K8sResourceUtils k8sResourceUtils,
                               final TestSuitePlugin testPlugin,
                               final MeterRegistry meterRegistry) {
        super(reconcilierObserver, "test", k8sResourceUtils, meterRegistry);
        this.testSuitePlugin = testPlugin;
    }

    @Override
    protected Completable doTask(Task task, DataCenter dc) throws Exception {
        if (testSuitePlugin.isBusy(task) && !testSuitePlugin.isRunning(task)) {
            // a test is already running, postpone this one
            return k8sResourceUtils.updateTaskStatus(task, TaskPhase.WAITING);
        }

        if (!testSuitePlugin.isRunning(task)) {
            // a test is already running, postpone this one
            return testSuitePlugin.initialize(task, dc);
        } else {
            testSuitePlugin.runTest(task, dc);
            return Completable.complete();
        }
    }

    @Override
    protected Completable validTask(Task task, DataCenter dc) throws Exception {
        if (testSuitePlugin.isRunning(task)) {
            testSuitePlugin.runTest(task, dc);
        }
        return Completable.complete();
    }
}
