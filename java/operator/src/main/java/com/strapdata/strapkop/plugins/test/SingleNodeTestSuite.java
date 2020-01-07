package com.strapdata.strapkop.plugins.test;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.*;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.plugins.test.step.OnSuccessAction;
import com.strapdata.strapkop.plugins.test.step.Step;
import com.strapdata.strapkop.utils.RestorePointCache;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1ResourceRequirements;
import io.micronaut.context.annotation.Prototype;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.strapdata.strapkop.plugins.test.step.StepFailedException.failed;

@Prototype
public class SingleNodeTestSuite extends TestSuiteExecutor {

    public static final Quantity UPDATED_CPU_QUANTITY = Quantity.fromString("2500m");

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
        return waitClusterUpdated(1, this::updateDataCenterCPUResources, false);
    }

    protected Step updateDataCenterCPUResources(DataCenter dc) {
        // update a DC Spec to trigger a new DC Generation without ConfigMap fingerprint change
        V1ResourceRequirements current = dc.getSpec().getResources();
        if (current == null) {
            current = new V1ResourceRequirements();
            dc.getSpec().setResources(current);
        }

        if (current.getLimits() == null) {
            current.limits(new HashMap<>());
        }

        if (current.getRequests() == null) {
            current.requests(new HashMap<>());
        }

        Quantity qtCPU = current.getLimits().get("cpu");
        if (qtCPU == null || !qtCPU.equals(UPDATED_CPU_QUANTITY)) {
            LOGGER.info("[TEST] Update the DC CPU Limit from '{}' to '{}'", qtCPU, UPDATED_CPU_QUANTITY);
            current.getLimits().put("cpu", UPDATED_CPU_QUANTITY);

            // set Request to Limit value to avoid Request > Limit that hang STS
            // TODO better to implement a check before applying STS changes and reject the DC update but how to do that... ?
            LOGGER.info("[TEST] Update the DC CPU Request from '{}' to '{}'", current.getRequests().get("cpu"), UPDATED_CPU_QUANTITY);
            current.getRequests().put("cpu", UPDATED_CPU_QUANTITY);

            updateDataCenterOrFail(dc);
            return waitClusterUpdated(1, this::shutdownTest, false);
        } else {
            LOGGER.info("[TEST] DC CPU Limit already set to '{}'", UPDATED_CPU_QUANTITY);
            return shutdownTest(dc); // call end of test
        }
    }
}