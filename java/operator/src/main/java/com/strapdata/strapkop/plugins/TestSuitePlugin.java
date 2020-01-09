package com.strapdata.strapkop.plugins;

import com.strapdata.dns.DnsConfiguration;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.k8s.task.TaskPhase;
import com.strapdata.model.k8s.task.TestTaskSpec;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.plugins.test.TestSuiteExecutor;
import com.strapdata.strapkop.plugins.test.TestSuiteHandler;
import com.strapdata.strapkop.reconcilier.TaskReconcilier;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.micronaut.context.ApplicationContext;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.concurrent.atomic.AtomicReference;

import static com.strapdata.strapkop.OperatorConfig.TestSuiteConfig.Platform;

@Singleton
public class TestSuitePlugin extends AbstractPlugin implements TestSuiteHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestSuitePlugin.class);

    private AtomicReference<TaskReconcilier.TaskWrapper> runningTask = new AtomicReference<>();
    private TestSuiteExecutor testExecutor;

    public TestSuitePlugin(final ApplicationContext context,
                           K8sResourceUtils k8sResourceUtils,
                           AuthorityManager authorityManager,
                           CoreV1Api coreApi,
                           AppsV1Api appsApi,
                           OperatorConfig config,
                           DnsConfiguration dnsConfiguration) {
        super(context, k8sResourceUtils, authorityManager, coreApi, appsApi, config, dnsConfiguration);
    }

    public TestSuiteExecutor getTestSuite(Platform platform, String testSuiteClass) {
        try {
            switch (platform) {
                case GKE:
                case AZURE:
                case LOCAL:
                    // for now there are no specific things
                    // maybe later we will have some helper per env
                    // to perform specific check in the testSuiteExecutor
                    return this.context.getBean(Class.forName(testSuiteClass).asSubclass(TestSuiteExecutor.class));
                default:
                // should never happen... :)
                throw new IllegalArgumentException("Unsupported platform '" + platform + "'");
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Invalid testSuite '" + testSuiteClass + "'");
        }
    }

    /**
     * return true if another task is already running
     * @param task
     * @return
     */
    public boolean isBusy(Task task) {
        return runningTask != null && runningTask.get() != null && runningTask.get().getTask() != null;
    }

    /**
     * return true if the provided task is running
     * @param task
     * @return
     */
    public boolean isRunning(Task task) {
        return runningTask != null && runningTask.get() != null  && runningTask.get().getTask() != null && runningTask.get().getTask().getMetadata().getName().equals(task.getMetadata().getName());
    }

    public void runTest(TaskReconcilier.TaskWrapper task, DataCenter dc) {
        if (isRunning(task.getTask())) {
            this.runningTask.set(task);
            this.testExecutor.executeFirstStep(task.getTask(), dc);
        }
    }

    /**
     * Start the testSuite execution
     *
     * @param taskWrapper
     * @param dc
     * @return
     */
    public Completable initialize(TaskReconcilier.TaskWrapper taskWrapper, DataCenter dc) {
        Task task = taskWrapper.getTask();
        if (operatorConfig.getTest().isEnabled()) {
            this.runningTask.set(taskWrapper);
            TestTaskSpec testSpec = task.getSpec().getTest();
            this.testExecutor = getTestSuite(operatorConfig.getTest().getPlatform(), testSpec.getTestSuite());
            this.testExecutor.setHandler(this);
            this.testExecutor.initialize(task, dc);
        } else {
            LOGGER.debug("[TEST] test plugin is disabled, ignore the TestTask");
            task.getStatus().setPhase(TaskPhase.SUCCEED);
            task.getStatus().setLastMessage("TestSuitePlugin disable, task ignored");
            updateTaskStatus(taskWrapper);
        }
        return Completable.complete();
    }

    @Override
    public boolean isActive(DataCenter dataCenter) {
        return operatorConfig.getTest().isEnabled();
    }

    @Override
    public boolean reconsileOnParkState() {
        return true;
    }

    @Override
    public void syncKeyspaces(CqlKeyspaceManager cqlKeyspaceManager, DataCenter dataCenter) {
        // do nothing
    }

    @Override
    public void syncRoles(CqlRoleManager cqlRoleManager, DataCenter dataCenter) {
        // do nothing
    }

    @Override
    public Completable reconcile(DataCenter dataCenter) throws ApiException, StrapkopException {
        if (operatorConfig.getTest().isEnabled() && hasRunningExecutor()) {
            return Completable.fromAction(() -> testExecutor.executeNextStep(this.runningTask.get().getTask(), dataCenter));
        } else {
            return Completable.complete();
        }
    }

    private boolean hasRunningExecutor() {
        return testExecutor != null && runningTask != null && runningTask.get() != null;
    }

    @Override
    public Completable delete(DataCenter dataCenter) throws ApiException {
        return Completable.complete(); // TODO do something useful
    }

    @Override
    public void onTimeout(int nbOfSteps) {
        this.testExecutor = null;
        LOGGER.warn("[TEST] Timeout after {} steps", nbOfSteps);
        this.runningTask.get().getTask().getStatus()
                .setPhase(TaskPhase.FAILED)
                .setLastMessage("Test Timeout after " + nbOfSteps + " steps");
        updateTaskStatus(this.runningTask.get());
    }

    @Override
    public void onEnd(int nbOfSteps) {
        this.testExecutor = null;
        LOGGER.info("[TEST] end after {} steps", nbOfSteps);
        this.runningTask.get().getTask().getStatus()
                .setPhase(TaskPhase.SUCCEED)
                .setLastMessage("Test OK. ("+nbOfSteps+" steps passed)");
        updateTaskStatus(this.runningTask.get());
    }

    @Override
    public void onFailure(int nbOfSteps, String message) {
        this.testExecutor = null;
        LOGGER.warn("[TEST] failure after {} steps with message '{}'", nbOfSteps, message);
        this.runningTask.get().getTask().getStatus()
                .setPhase(TaskPhase.FAILED)
                .setLastMessage("Test KO. ("+nbOfSteps+" steps passed / error : "+message+")");
        updateTaskStatus(this.runningTask.get());
    }

    private void updateTaskStatus(TaskReconcilier.TaskWrapper taskWrapper) {
        try {
            k8sResourceUtils.updateTaskStatus(taskWrapper).subscribe(
                    () -> {
                        this.runningTask = new AtomicReference<>();
                    },
                    (t) -> {
                        this.runningTask = new AtomicReference<>();
                        LOGGER.warn("[TEST] Update status of TestTask failed", t);
                    });
        } catch (ApiException ae) {
            this.runningTask = null;
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[TEST] Update status of TestTask failed with code '{}' and Body '{}'", ae.getCode(), ae.getResponseBody());
            } else {
                LOGGER.warn("[TEST] Update status of TestTask failed with code '{}'", ae.getCode());
            }
        }
    }
}