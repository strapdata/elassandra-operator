package com.strapdata.strapkop.plugins.test;

import com.strapdata.model.k8s.cassandra.CqlStatus;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterStatus;
import com.strapdata.model.k8s.cassandra.RackPhase;
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
            try {
                LOGGER.info("[TEST] Trigger the creation of the elassandra node .");
                k8sResourceUtils.updateDataCenter(dc).subscribe();
            } catch (ApiException e) {
                LOGGER.error("[TEST] unable to update the DC replicas [code : {} | body : {}]", e.getCode(), e.getResponseBody());
                failed(e.getMessage());
            }
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
                    nextStep = checkNodeAvailability(dc,1, enableReaper());
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
    protected Step checkNodeAvailability(final DataCenter dc, final int expectedReplicas, final OnSuccessAction onSuccess) {
        final DataCenterStatus status = dc.getStatus();
        // filter on NORMAL nodes
        List<String> nodeNames = status.getElassandraNodeStatuses().entrySet().stream()
                .filter(e -> Objects.equals(e.getValue(), ElassandraNodeStatus.NORMAL))
                .map(e -> e.getKey()).collect(Collectors.toList());

        if (nodeNames.size() != expectedReplicas) {
            LOGGER.info("[TEST] {}/{} nodes in NORMAL state, waiting... ", nodeNames.size(), expectedReplicas);
            return this::waitRunningDcPhase; // go back to previous state // TODO pass this in parameters
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
                try {
                    dc.getSpec().setReaperEnabled(true);
                    k8sResourceUtils.updateDataCenter(dc).subscribe();
                    LOGGER.debug("[TEST] Reaper enabled");
                } catch (ApiException e) {
                    LOGGER.error("[TEST] unable to activate reaper [code : {} | body : {}]", e.getCode(), e.getResponseBody());
                    failed(e.getMessage());
                }
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
                nextStep = shutdownTest(dc);// FINAL STEP for now
                break;
        }
        return nextStep;
    }

}