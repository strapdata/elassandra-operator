package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.k8s.cassandra.BlockReason;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.plugins.TestSuitePlugin;
import io.micrometer.core.instrument.MeterRegistry;
import io.reactivex.Completable;
import io.reactivex.Single;

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

    public BlockReason blockReason() {
        return BlockReason.NONE;
    }

    @Override
    protected Single<TaskPhase> doTask(TaskWrapper taskWrapper, DataCenter dc) throws Exception {
        final Task task = taskWrapper.getTask();

        if (testSuitePlugin.isBusy(task) && !testSuitePlugin.isRunning(task)) {
            // a test is already running, postpone this one
            return Single.just(TaskPhase.WAITING);
        }

        if (!testSuitePlugin.isBusy(task)) {
            testSuitePlugin.initialize(taskWrapper, dc);
            return Single.just(TaskPhase.RUNNING);
        }

        testSuitePlugin.runTest(taskWrapper, dc);
        return Single.just(TaskPhase.RUNNING);
    }

    @Override
    protected Completable validTask(TaskWrapper taskWrapper, DataCenter dc) throws Exception {
        if (testSuitePlugin.isRunning(taskWrapper.getTask())) {
            testSuitePlugin.runTest(taskWrapper, dc);
        }
        return Completable.complete();
    }
}
