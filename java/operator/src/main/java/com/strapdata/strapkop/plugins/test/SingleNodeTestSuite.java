package com.strapdata.strapkop.plugins.test;

import com.strapdata.model.k8s.cassandra.*;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.plugins.test.step.OnSuccessAction;
import com.strapdata.strapkop.plugins.test.step.Step;
import io.kubernetes.client.ApiException;
import io.micronaut.context.annotation.Prototype;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.strapdata.strapkop.plugins.test.step.StepFailedException.failed;

@Prototype
public class SingleNodeTestSuite extends TestSuiteExecutor {
    @Override
    protected Step initialStep() {
        return this::createReplicas;
    }

    protected Step createReplicas(DataCenter dc) {
        if (dc.getSpec().getReplicas() <= 0) {
            dc.getSpec().setReplicas(1);
            LOGGER.info("[TEST] Trigger the creation of the elassandra node .");
            updateDataCenterOrFail(dc);
            return this::waitRunningDcPhase;
        } else {
            LOGGER.info("[TEST] Replicas already configured, execute nextStep");
            return waitRunningDcPhase(dc);
        }
    }

    protected Step waitRunningDcPhase(DataCenter dc) {
        Step nextStep = this::waitRunningDcPhase;

        DataCenterStatus dcStatus = dc.getStatus();
        if (dcStatus != null && dcStatus.getPhase() != null) {
            switch (dcStatus.getPhase()) {
                case ERROR:
                    LOGGER.info("[TEST] ScaleUp failed... DatacenterPhase is {}", dcStatus.getPhase());
                    failed("ScaleUp failed : " + dcStatus.getLastMessage());
                    break;
                case RUNNING:
                    LOGGER.info("[TEST] DataCenter is now Running ...");
                    // check Node availability, if OK enableReaper, otherwise wait using this step
                    nextStep = checkNodeAvailability(dc,1, enableReaper(), this::waitRunningDcPhase);
                    break;
                case CREATING:
                case SCALING_UP:
                    LOGGER.info("[TEST] DataCenter is scaling up ...");
                    nextStep = this::waitRunningDcPhase;
                    break;
                default:
                    LOGGER.info("[TEST] ScaleUp failed... DatacenterPhase is {}", dcStatus.getPhase());
                    failed("Unexpected State : " + dcStatus.getPhase());
            }
        }

        return nextStep;
    }

    // TODO move to super class ??
    protected Step checkNodeAvailability(final DataCenter dc, final int expectedReplicas, final OnSuccessAction onSuccess, final Step waitingStep) {
        final DataCenterStatus status = dc.getStatus();
        // filter on NORMAL nodes
        List<String> nodeNames = status.getElassandraNodeStatuses().entrySet().stream()
                .filter(e -> Objects.equals(e.getValue(), ElassandraNodeStatus.NORMAL))
                .map(e -> e.getKey()).collect(Collectors.toList());

        if (nodeNames.size() != expectedReplicas) {
            LOGGER.info("[TEST] {}/{} nodes in NORMAL state, waiting... ", nodeNames.size(), expectedReplicas);
            return this::waitRunningDcPhase;
        } else {
            LOGGER.info("[TEST] {}/{} nodes in NORMAL state, other values... ", nodeNames.size(), expectedReplicas);

            // TODO foreach node, check pod existence

            assertEquals("CQL Status should be ESTABLISHED", CqlStatus.ESTABLISHED, status.getCqlStatus());

            assertEquals("Expected " + expectedReplicas + " Replicas", expectedReplicas, status.getReplicas());
            assertEquals("Expected " + expectedReplicas + " ReadyReplicas", expectedReplicas, status.getReadyReplicas());
            assertEquals("Expected " + expectedReplicas + " JoinedReplicas", expectedReplicas, status.getJoinedReplicas());

            // TODO refactor as handler to test RackStatuses based on provider
            assertEquals("Expected " + expectedReplicas + " RackStatus", expectedReplicas, status.getRackStatuses().size());
            status.getRackStatuses().forEach((rackStatus) -> {
                assertEquals("Expected " + expectedReplicas + " RackPhase", RackPhase.RUNNING, rackStatus.getPhase());
                assertEquals("Expected " + expectedReplicas + " JoinedReplicas", expectedReplicas, rackStatus.getJoinedReplicas());
            });

            return onSuccess.execute(dc);
        }
    }

    protected OnSuccessAction enableReaper() {
        return (dc) -> {
            Step nextStep = this::waitReaperRegistered;
            if (!dc.getSpec().getReaperEnabled()) {
                LOGGER.debug("[TEST] Update DC to enable Reaper");
                dc.getSpec().setReaperEnabled(true);
                updateDataCenterOrFail(dc);
                LOGGER.debug("[TEST] Reaper enabled");
            } else {
                LOGGER.debug("[TEST] Reaper already enabled, exec next step");
                // reaper already enabled, execute next step to avoid timeout
                // because if the DC already in stable state, we may never receive reconciliation
                nextStep = waitReaperRegistered(dc);
            }
            return nextStep;
        };
    }

    protected Step waitReaperRegistered(DataCenter dc) {
        Step nextStep = this::waitReaperRegistered;
        switch (dc.getStatus().getReaperPhase()) {
            case NONE:
            case ROLE_CREATED:
            case KEYSPACE_CREATED:
                LOGGER.info("[TEST] Reaper not ready, waiting...");
                break;
            case REGISTERED:
                LOGGER.info("[TEST] Reaper registered");
                assertTrue("Reaper pod exist", podExists(dc, OperatorLabels.APP, "reaper"));
                nextStep = updateSpecConfigMap(dc); // execute updateSpecConfig
                break;
        }
        return nextStep;
    }

    protected Step updateSpecConfigMap(DataCenter dc) {
        // update a DC value to trigger a new finger print
        ElassandraWorkload current = dc.getSpec().getWorkload();
        switch (current){
            case WRITE:
                dc.getSpec().setWorkload(ElassandraWorkload.READ);
                break;
            case READ:
            case READ_WRITE:
                dc.getSpec().setWorkload(ElassandraWorkload.WRITE);
                break;
        }
        LOGGER.info("[TEST] Update the DC workload from '{}' to '{}'", current, dc.getSpec().getWorkload());
        updateDataCenterOrFail(dc);
        return waitClusterUpdated(false);
    }

    protected Step waitClusterUpdated(boolean phaseHasBeenUpdating) {
        return (dc) -> {
            Step nextStep = null;
            switch (dc.getStatus().getPhase()) {
                case UPDATING:
                    LOGGER.info("[TEST] DC is updating the configuration, waiting...");
                    nextStep = waitClusterUpdated(true);
                    break;
                case ERROR:
                    LOGGER.info("[TEST] DC update failed");
                    failed("DC update failed with DataCenterPhase set to ERROR");
                    break;

                case CREATING:
                case EXECUTING_TASK:
                    LOGGER.info("[TEST] Unexpected DC Phase");
                    failed("Unexpected DC Phase during config map update ('" + dc.getStatus().getPhase() + "')");
                    break;

                case RUNNING:
                    if (phaseHasBeenUpdating) {
                        LOGGER.info("[TEST] DC Phase is now in Phase RUNNING after UPDATING one");
                        // check Node availability, if OK test will finish, otherwise wait using this step
                        return checkNodeAvailability(dc, 1, this::shutdownTest, waitClusterUpdated(true));
                    } else {
                        failed("Unexpected DC Phase RUNNING without UPDATING one");
                    }
                    break;
            }
            return nextStep;
        };
    }

    private void updateDataCenterOrFail(DataCenter dc) {
        try {
            k8sResourceUtils.updateDataCenter(dc).subscribe();
        } catch (ApiException e) {
            LOGGER.error("[TEST] unable to update DataCenter [code : {} | body : {}]", e.getCode(), e.getResponseBody());
            failed(e.getMessage());
        }
    }

}