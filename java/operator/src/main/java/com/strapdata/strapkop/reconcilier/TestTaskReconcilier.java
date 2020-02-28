package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.model.k8s.cassandra.BlockReason;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.plugins.TestSuitePlugin;
import io.micrometer.core.instrument.MeterRegistry;
import io.reactivex.Completable;
import io.reactivex.Single;

import javax.inject.Singleton;

@Singleton
public class TestTaskReconcilier extends TaskReconcilier {

    private final TestSuitePlugin testSuitePlugin;

    public TestTaskReconcilier(ReconcilierObserver reconcilierObserver,
                               final DataCenterUpdateReconcilier dataCenterUpdateReconcilier,
                               final K8sResourceUtils k8sResourceUtils,
                               final TestSuitePlugin testPlugin,
                               final WorkQueues workQueue,
                               final MeterRegistry meterRegistry) {
        super(reconcilierObserver, "test", k8sResourceUtils, meterRegistry, dataCenterUpdateReconcilier);
        this.testSuitePlugin = testPlugin;
    }

    public BlockReason blockReason() {
        return BlockReason.NONE;
    }

    @Override
    protected Single<TaskPhase> doTask(final Task task, final DataCenter dc) throws Exception {
        if (testSuitePlugin.isBusy(task) && !testSuitePlugin.isRunning(task)) {
            // a test is already running, postpone this one
            return Single.just(TaskPhase.WAITING);
        }

        if (!testSuitePlugin.isBusy(task)) {
            testSuitePlugin.initialize(task, dc);
            return Single.just(TaskPhase.RUNNING);
        }

        testSuitePlugin.runTest(task, dc);
        return Single.just(TaskPhase.RUNNING);
    }

    @Override
    protected Completable validTask(final Task task, final DataCenter dc) throws Exception {
        if (testSuitePlugin.isRunning(task)) {
            testSuitePlugin.runTest(task, dc);
        }
        return Completable.complete();
    }
}
