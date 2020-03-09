package com.strapdata.strapkop.plugins.test;

import com.strapdata.strapkop.cache.CheckPointCache;
import com.strapdata.strapkop.cache.ElassandraNodeStatusCache;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceTestUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.cassandra.*;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.plugins.test.step.OnSuccessAction;
import com.strapdata.strapkop.plugins.test.step.Step;
import com.strapdata.strapkop.plugins.test.step.StepFailedException;
import com.strapdata.strapkop.plugins.test.util.ESRestClient;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Prototype;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.strapdata.strapkop.plugins.test.step.StepFailedException.failed;

@Prototype
public abstract class TestSuiteExecutor {

    protected static final Logger LOGGER = LoggerFactory.getLogger(TestSuiteExecutor.class);

    @Inject
    protected ApplicationContext context;

    @Inject
    protected K8sResourceTestUtils k8sResourceUtils;

    @Inject
    protected AuthorityManager authorityManager;

    @Inject
    protected CoreV1Api coreApi;

    @Inject
    protected AppsV1Api appsApi;

    @Inject
    protected CqlRoleManager cqlRoleManager;

    @Inject
    ElassandraNodeStatusCache elassandraNodeStatusCache;

    @Inject
    private CheckPointCache checkPointCache;

    private Timer timer = new Timer();
    protected Step currentStep;
    private int stepCounter = 0;

    @Getter
    private Task task;
    @Setter
    private TestSuiteHandler handler;

    protected TestSuiteExecutor() {
        this.currentStep = initialStep();
    }

    protected abstract Step initialStep();

    public final void initialize(Task task, DataCenter dc) {
        final long timeout = task.getSpec().getTest().getTimeOut();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("[TEST] Start execution of '{}' with golbal timeOut of '{}'", this.getClass().getSimpleName(), timeout);
        }
        this.task = task;
        this.timer.schedule(new TimerTask() {
            @Override
            public void run() {
                handler.onTimeout(stepCounter);
            }
        }, timeout);
    }

    public final void executeFirstStep(Task task, DataCenter dc) {
        if (stepCounter == 0) {
            executeNextStep(task, dc);
        }
    }

    public final void executeNextStep(Task task, DataCenter dc) {
        try {
            stepCounter++;
            if (this.currentStep != null) {
                this.currentStep = this.currentStep.execute(dc);
            }
        } catch (StepFailedException e) {
            LOGGER.warn("[TEST] Test '{}' failed on step {} : '{}' ", this.getClass().getSimpleName(), stepCounter, e.getMessage());
            handler.onFailure(stepCounter, e.getMessage());
            timer.cancel();
        }
    }

    protected final Step shutdownTest(DataCenter dc) throws StepFailedException {
        LOGGER.info("[TEST] succeeded with {} steps", stepCounter);
        handler.onEnd(stepCounter);
        timer.cancel();
        return null;
    }

    protected final void assertEquals(String message, Object expected, Object value) {
        if (!Objects.equals(expected, value)) failed(message + " [<" + expected.toString() +"> != <" + value.toString() +">]" );
    }

    protected final void assertFalse(String message, boolean value) {
        assertTrue(message, !value);
    }

    protected final void assertTrue(String message, boolean value) {
        if (!value) failed(message + ": boolean assertion not verified");
    }


    protected boolean podExists(DataCenter dc, String prodName) {
        try {
            return k8sResourceUtils.podExists(dc.getMetadata().getNamespace(), prodName);
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                failed("ApiException on pod existence test : [code="+e.getCode()+" | boyd=" + e.getResponseBody() +"]");
            }
            return false;
        }
    }

    protected boolean podExists(DataCenter dc, String label, String value) {
        try {
            return k8sResourceUtils.podExists(dc.getMetadata().getNamespace(), OperatorLabels.APP, value);
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                failed("ApiException on pod existence test : [code="+e.getCode()+" | boyd=" + e.getResponseBody() +"]");
            }
            return false;
        }
    }


    protected Step checkNodeAvailability(final DataCenter dc, final int expectedReplicas, final OnSuccessAction onSuccess, final Step waitingStep) {
        final DataCenterStatus status = dc.getStatus();
        // filter on NORMAL nodes
        List<String> nodeNames = elassandraNodeStatusCache.entrySet().stream()
                .filter(e -> e.getKey().getNamespace().equals(dc.getMetadata().getNamespace()) &&
                        e.getKey().getCluster().equals(dc.getSpec().getClusterName()) &&
                        e.getKey().getDataCenter().equals(dc.getSpec().getDatacenterName()))
                .filter(e -> Objects.equals(e.getValue(), ElassandraNodeStatus.NORMAL))
                .map(e -> e.getKey().getName())
                .collect(Collectors.toList());



        if (nodeNames.size() != expectedReplicas) {
            LOGGER.info("[TEST] {}/{} nodes in NORMAL state, waiting... ", nodeNames.size(), expectedReplicas);
            return waitingStep;
        } else {

            try {
                final Iterable<Task> tasks = k8sResourceUtils.listNamespacedTask(dc.getMetadata().getNamespace(), OperatorLabels.toSelector(OperatorLabels.datacenter(dc)));
                final long count = StreamSupport.stream(tasks.spliterator(), false)
                        .filter(task -> TaskPhase.RUNNING.equals(task.getStatus().getPhase()))
                        .filter(task -> task.getSpec().getTest() == null) // exclude the TestSuite Tasks
                        .count();
                if (count > 0) {
                    LOGGER.info("[TEST] {} tasks are RUNNING ", count);
                    return waitingStep;
                }
            } catch (ApiException e) {
                LOGGER.warn("[TEST] Unable to list tasks, try to continue...", e);
            }

            LOGGER.info("[TEST] {}/{} nodes in NORMAL state, other values... ", nodeNames.size(), expectedReplicas);

            assertEquals("CQL Status should be ESTABLISHED", CqlStatus.ESTABLISHED, status.getCqlStatus());

            assertEquals("Expected " + expectedReplicas + " Replicas", expectedReplicas, status.getReplicas());
            assertEquals("Expected " + expectedReplicas + " ReadyReplicas", expectedReplicas, status.getReadyReplicas());

            assertEquals("Expected at least one RackStatus", true, status.getRackStatuses().size() >= 1);
            status.getRackStatuses().values().forEach((rackStatus) -> {
                assertEquals("Expected " + expectedReplicas + " RackPhase", RackPhase.RUNNING, rackStatus.getPhase());
            });

            checkHistoryDataCenter(dc);

            return onSuccess.execute(dc);
        }
    }

    /**
     * Check that the DCSpec is stored in RestorePointCache.
     * @param dc
     */
    protected void checkHistoryDataCenter(final DataCenter dc) {
        Optional<CheckPointCache.CheckPoint> restorePoint = checkPointCache.getCheckPoint(new Key(dc.getMetadata()));
        if (restorePoint.isPresent()) {
            if (restorePoint.get().getCommittedSpec() == null) {
                failed("ElassandraDatacenter should have a restore point");
            }

            String stableFingerPrint = restorePoint.get().getCommittedSpec().fingerprint();
            if (!dc.getSpec().fingerprint().equals(stableFingerPrint)) {
                failed("ElassandraDatacenter RestorePoint instance should reference the fingerprint " + dc.getSpec().fingerprint());
            }
        }
    }

    /**
     *
     * @param onNodeAvailable action returning a Step to call if waitClusterUpdated succeeded
     * @param phaseHasBeenUpdating flag to keep track of DC Phase changes (true if UPDATING phase has been checked)
     * @return
     */
    protected Step waitClusterUpdated(int expectedReplicas, OnSuccessAction onNodeAvailable, boolean phaseHasBeenUpdating) {
        return (dc) -> {
            Step nextStep = waitClusterUpdated(expectedReplicas, onNodeAvailable, true);
            switch (dc.getStatus().getPhase()) {
                case UPDATING:
                    LOGGER.info("[TEST] DC is updating the configuration, waiting...");
                    nextStep = waitClusterUpdated(expectedReplicas, onNodeAvailable, true);
                    break;
                case ERROR:
                    LOGGER.info("[TEST] DC update failed");
                    failed("DC update failed with DataCenterPhase set to ERROR");
                    break;

                case CREATING:
                    LOGGER.info("[TEST] Unexpected DC Phase");
                    failed("Unexpected DC Phase during config map update ('" + dc.getStatus().getPhase() + "')");
                    break;

                case RUNNING:
                    if (phaseHasBeenUpdating) {
                        LOGGER.info("[TEST] DC Phase is now in Phase RUNNING after UPDATING one");
                        // check Node availability, if OK test will finish, otherwise wait using this step
                        return checkNodeAvailability(dc, expectedReplicas, onNodeAvailable, waitClusterUpdated(expectedReplicas, onNodeAvailable,true));
                    } else {
                        failed("Unexpected DC Phase RUNNING without UPDATING one");
                    }
                    break;
                default:
                    LOGGER.info("[TEST] waitClusterUpdated (found Phase {})", dc.getStatus().getPhase());
            }
            return nextStep;
        };
    }


    protected void updateDataCenterOrFail(DataCenter dc) {
        try {
            k8sResourceUtils.updateDataCenter(dc).blockingGet();
        } catch (ApiException e) {
            LOGGER.error("[TEST] unable to update DataCenter [code : {} | body : {}]", e.getCode(), e.getResponseBody());
            failed(e.getMessage());
        }
    }

    protected Step checkNodeParked(final DataCenter dc, final int expectedReplicas, final OnSuccessAction onSuccess, final Step waitingStep) {
        if (!DataCenterPhase.PARKED.equals(dc.getStatus().getPhase())) {
            LOGGER.info("[TEST] DC Phase is {} instead of {}, waiting... ", dc.getStatus(), DataCenterPhase.PARKED);
            return waitingStep;
        }

        Map<String, String> labels = new HashMap<>(OperatorLabels.datacenter(dc));
        labels.put(OperatorLabels.APP, "elassandra");
        try {
            if (k8sResourceUtils.listNamespacedPods(dc.getMetadata().getNamespace(), null, OperatorLabels.toSelector(labels)).iterator().hasNext()) {
                LOGGER.info("[TEST] PARKED dc still have existing pods, waiting... ");
                return waitingStep;
            }
        } catch(ApiException e) {
            failed(e.getMessage());
        }

        // a parked DC must have all replicas marked as parked
        Map<Integer, RackStatus> rackStatuses = dc.getStatus().getRackStatuses();
        assertEquals("Parked rack should have parked replicas", dc.getSpec().getReplicas(),
                rackStatuses.values().stream()
                        .map(rackStatus -> rackStatus.getParkedReplicas())
                        .reduce(0,Integer::sum));

        return onSuccess.execute(dc);
    }

    protected final void executeESRequest(final DataCenter dc, ESRequestProcessor processor ) {
        Map.Entry<ElassandraPod, ElassandraNodeStatus> entry = this.elassandraNodeStatusCache.entrySet().stream()
                .filter(e -> e.getKey().getNamespace().equals(dc.getMetadata().getNamespace()) &&
                        e.getKey().getCluster().equals(dc.getSpec().getClusterName()) &&
                        e.getKey().getDataCenter().equals(dc.getSpec().getDatacenterName()))
                .filter(e -> ElassandraNodeStatus.NORMAL.equals(e.getValue()))
                .findFirst()
                .get();
        String podFqdn = entry.getKey().getFqdn();
        String eslogin = null;
        String esPwd = null;
        if (Authentication.CASSANDRA.equals(dc.getSpec().getAuthentication())) {
            eslogin = "cassandra";
            esPwd =  retrieveCassandraPassword(dc);
        }
        try (ESRestClient client = new ESRestClient(podFqdn, dc.getSpec().getElasticsearchPort(), dc.getSpec().getEnterprise().getHttps(), eslogin, esPwd)) {
            processor.execute(dc, client);
        } catch (IOException e) {
            failed("ESRequest processor failure : " + e.getMessage());
        }
    }

    protected final String retrieveCassandraPassword(DataCenter dc) {
        return new String(k8sResourceUtils.readNamespacedSecret(dc.getMetadata().getNamespace(), OperatorNames.clusterSecret(dc)).blockingGet().getData().get("cassandra.cassandra_password"));
    }

    @FunctionalInterface
    public static interface ESRequestProcessor {
        void execute(DataCenter dc, ESRestClient client) throws StepFailedException, IOException;
    }
}