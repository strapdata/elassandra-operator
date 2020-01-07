package com.strapdata.strapkop.plugins.test;

import com.strapdata.model.k8s.cassandra.*;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.plugins.test.step.OnSuccessAction;
import com.strapdata.strapkop.plugins.test.step.Step;
import com.strapdata.strapkop.utils.RestorePointCache;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.models.V1ResourceRequirements;
import io.micronaut.context.annotation.Prototype;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.strapdata.strapkop.plugins.test.step.StepFailedException.failed;

/**
 * Initial config : 2 Nodes
 * -> enabled Reaper
 * -> change DC configuration
 * -> scale up to 3 replicas
 * -> scale down to 2 replicas
 * -> park replicas
 * -> unpark replicas
 */
@Prototype
public class ThreeNodesTestSuite extends TestSuiteExecutor {

    public static final int INITIAL_NUMBER_OF_REPLICAS = 2;
    public static final int MAX_NUMBER_OF_REPLICAS = 3;

    public static final Quantity UPDATED_CPU_QUANTITY = Quantity.fromString("2500m");

    private int currentExpectedReplicas = INITIAL_NUMBER_OF_REPLICAS;
    @Override
    protected Step initialStep() {
        return this::createReplicas;
    }

    protected Step createReplicas(DataCenter dc) {
        if (dc.getSpec().getReplicas() <= 0) {
            dc.getSpec().setReplicas(currentExpectedReplicas);
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
                    nextStep = checkNodeAvailability(dc, currentExpectedReplicas, enableReaper(), this::waitRunningDcPhase);
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


    protected OnSuccessAction enableReaper() {
        return (dc) -> {
            Step nextStep = this::waitReaperRegistered;
            if (!dc.getSpec().getReaper().getEnabled()) {
                LOGGER.debug("[TEST] Update DC to enable Reaper");
                dc.getSpec().getReaper().setEnabled(true);
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
        Workload current = dc.getSpec().getWorkload();
        switch (current){
            case WRITE:
                dc.getSpec().setWorkload(Workload.READ);
                break;
            case READ:
            case READ_WRITE:
                dc.getSpec().setWorkload(Workload.WRITE);
                break;
        }
        LOGGER.info("[TEST] Update the DC workload from '{}' to '{}'", current, dc.getSpec().getWorkload());
        updateDataCenterOrFail(dc);
        // wait before cluster update, on success process updateDataCenterCPUResources
        return waitClusterUpdated(currentExpectedReplicas, this::scaleUp, false);
    }

    protected Step scaleUp(DataCenter dc) {
        int previousReplicas = this.currentExpectedReplicas;
        this.currentExpectedReplicas = MAX_NUMBER_OF_REPLICAS;

        LOGGER.info("[TEST] Update the DC Replicas from '{}' to '{}'", previousReplicas, currentExpectedReplicas);
        dc.getSpec().setReplicas(this.currentExpectedReplicas);
        updateDataCenterOrFail(dc);

        return waitScaleUp(this.currentExpectedReplicas, this::scaleDown, false);
    }

    protected Step waitScaleUp(int expectedReplicas, OnSuccessAction onNodeAvailable, boolean phaseHasbeenScalingUp) {
        return (dc) -> {
            Step nextStep = null;
            switch (dc.getStatus().getPhase()) {
                case SCALING_UP:
                    LOGGER.info("[TEST] DC is scaling up the configuration, waiting...");
                    nextStep = waitScaleUp(expectedReplicas, onNodeAvailable, true);
                    break;
                case ERROR:
                    LOGGER.info("[TEST] DC scale up failed");
                    failed("DC scaling up failed with DataCenterPhase set to ERROR");
                    break;

                case RUNNING:
                    if (phaseHasbeenScalingUp) {
                        LOGGER.info("[TEST] DC Phase is now in Phase RUNNING after SCALING_UP one");
                        // check Node availability, if OK test will finish, otherwise wait using this step
                        return checkNodeAvailability(dc, expectedReplicas, onNodeAvailable, waitScaleUp(expectedReplicas, onNodeAvailable,true));
                    } else {
                        failed("Unexpected DC Phase RUNNING without SCALING_UP one");
                    }
                    break;

                default:
                    LOGGER.info("[TEST] Unexpected DC Phase");
                    failed("Unexpected DC Phase during scaling up ('" + dc.getStatus().getPhase() + "')");
                    break;
            }
            return nextStep;
        };
    }

    protected Step scaleDown(DataCenter dc) {
        int previousReplicas = this.currentExpectedReplicas;
        this.currentExpectedReplicas = INITIAL_NUMBER_OF_REPLICAS;

        LOGGER.info("[TEST] Update the DC Replicas from '{}' to '{}'", previousReplicas, currentExpectedReplicas);
        dc.getSpec().setReplicas(this.currentExpectedReplicas);
        updateDataCenterOrFail(dc);

        return waitScaleUp(this.currentExpectedReplicas, this::park, false);
    }

    protected Step waitScaleDown(int expectedReplicas, OnSuccessAction onNodeAvailable, boolean phaseHasbeenScalingUp) {
        return (dc) -> {
            Step nextStep = null;
            switch (dc.getStatus().getPhase()) {
                case SCALING_DOWN:
                    LOGGER.info("[TEST] DC is scaling down the configuration, waiting...");
                    nextStep = waitScaleDown(expectedReplicas, onNodeAvailable, true);
                    break;
                case ERROR:
                    LOGGER.info("[TEST] DC scale down failed");
                    failed("DC scaling down failed with DataCenterPhase set to ERROR");
                    break;

                case RUNNING:
                    if (phaseHasbeenScalingUp) {
                        LOGGER.info("[TEST] DC Phase is now in Phase RUNNING after SCALING_DOWN one");
                        // check Node availability, if OK test will finish, otherwise wait using this step
                        return checkNodeAvailability(dc, expectedReplicas, onNodeAvailable, waitScaleDown(expectedReplicas, onNodeAvailable,true));
                    } else {
                        failed("Unexpected DC Phase RUNNING without SCALING_DOWN one");
                    }
                    break;

                default:
                    LOGGER.info("[TEST] Unexpected DC Phase");
                    failed("Unexpected DC Phase during scaling down ('" + dc.getStatus().getPhase() + "')");
                    break;
            }
            return nextStep;
        };
    }

    protected Step park(DataCenter dc) {
        LOGGER.info("[TEST] Park ");
        dc.getSpec().setParked(true);
        updateDataCenterOrFail(dc);

        return waitParked(this.currentExpectedReplicas, this::unpark, false);
    }

    protected Step waitParked(int expectedReplicas, OnSuccessAction onNodeParked, boolean phaseHasBeenParking) {
        return (dc) -> {
            Step nextStep = null;
            switch (dc.getStatus().getPhase()) {
                case PARKING:
                    LOGGER.info("[TEST] DC is parking, waiting...");
                    nextStep = waitParked(expectedReplicas, onNodeParked, true);
                    break;
                case ERROR:
                    LOGGER.info("[TEST] DC parking failed");
                    failed("DC parking failed with DataCenterPhase set to ERROR");
                    break;

                case PARKED:
                    if (phaseHasBeenParking) {
                        LOGGER.info("[TEST] DC Phase is now in Phase PARKED after SCALING_DOWN one");
                        // check Node availability, if OK test will finish, otherwise wait using this step
                        return checkNodeParked(dc, expectedReplicas, onNodeParked, waitParked(expectedReplicas, onNodeParked,true));
                    } else {
                        failed("Unexpected DC Phase RUNNING without PARKING one");
                    }
                    break;

                default:
                    LOGGER.info("[TEST] Unexpected DC Phase");
                    failed("Unexpected DC Phase during parking ('" + dc.getStatus().getPhase() + "')");
                    break;
            }
            return nextStep;
        };
    }

    protected Step unpark(DataCenter dc) {
        LOGGER.info("[TEST] Unpark ");
        dc.getSpec().setParked(false);
        updateDataCenterOrFail(dc);

        return waitClusterUpdated(this.currentExpectedReplicas, this::shutdownTest, false);
    }

}