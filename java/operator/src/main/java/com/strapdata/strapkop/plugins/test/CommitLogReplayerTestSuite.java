package com.strapdata.strapkop.plugins.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterStatus;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.plugins.test.step.OnSuccessAction;
import com.strapdata.strapkop.plugins.test.step.Step;
import io.kubernetes.client.ApiException;
import io.micronaut.context.annotation.Prototype;
import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.strapdata.strapkop.plugins.test.step.StepFailedException.failed;

@Prototype
public class CommitLogReplayerTestSuite extends TestSuiteExecutor {

    public static final int INITIAL_NUMBER_OF_REPLICAS = 2;
    private int currentExpectedReplicas = INITIAL_NUMBER_OF_REPLICAS;

    private static final int NUMBER_OF_DOC_PER_BATCH = 10000;
    private static final String KEYSPACE = "TwoNodesWithWorkloadTestSuite".toLowerCase();
    private static final String TABLE = "TestTable".toLowerCase();

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
                    nextStep = checkNodeAvailability(dc, currentExpectedReplicas, insertDocumentAndKillPod(), this::waitRunningDcPhase);
                    break;
                case CREATING:
                case SCALING_UP:
                case SCALING_DOWN:
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


    protected OnSuccessAction insertDocumentAndKillPod() {
        return (dc) -> {
            LOGGER.info("[TEST] Insert {} documents", NUMBER_OF_DOC_PER_BATCH);
            executeESRequest(dc, (d, client) -> {

                ExecutorService executor = Executors.newFixedThreadPool(10);
                List<Future<Map<String, String>>> collect = new ArrayList<>();
                for (int i =0; i < NUMBER_OF_DOC_PER_BATCH; i++) {
                    final int index = i;
                    Callable<Map<String, String>> call = new Callable<Map<String, String>>() {
                        @Override
                        public Map<String, String> call() throws Exception {
                            Map<String, String> doc = new HashMap<>();
                            doc.put("entry1", "" + index);
                            doc.put("entry2", "some" + index);
                            doc.put("entry3", "other" + index);
                            client.upload(KEYSPACE, TABLE, doc);
                            return doc;
                        }
                    };

                    collect.add(executor.submit(call));
                }

                for (Future<Map<String, String>> f : collect){
                    try {
                        f.get();
                    } catch (Exception e) {}
                };
                executor.shutdownNow();

                client.refresh(KEYSPACE);

                JsonNode node = client.getDocuments(KEYSPACE, TABLE, "{}");
                assertTrue("Wrong number of Hits after insert", NUMBER_OF_DOC_PER_BATCH <= node.path("hits").path("total").intValue());
            });

            LOGGER.debug("[TEST] Update DC to enable Reaper");

            String pod = dc.getStatus().getElassandraNodeStatuses().entrySet().stream().filter(e -> ElassandraNodeStatus.NORMAL.equals(e.getValue())).map(e -> e.getKey()).findFirst().get();
            try {
                k8sResourceUtils.deletePod(dc.getMetadata().getNamespace(), pod);
            } catch (ApiException e) {
                failed("Pod '" + pod + "' can't be deleted : " + e.getMessage());
            }
            return this::waitRunningDcPhase2;
        };
    }

    protected Step waitRunningDcPhase2(DataCenter dc) {
        Step nextStep = this::waitRunningDcPhase2;

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
                    nextStep = checkNodeAvailability(dc, currentExpectedReplicas, checkHintsAfterRestart(), this::waitRunningDcPhase2);
                    break;
                case CREATING:
                case SCALING_UP:
                case SCALING_DOWN:
                    LOGGER.info("[TEST] DataCenter is scaling up ...");
                    nextStep = this::waitRunningDcPhase2;
                    break;
                default:
                    LOGGER.info("[TEST] ScaleUp failed... DatacenterPhase is {}", dcStatus.getPhase());
                    failed("Unexpected State : " + dcStatus.getPhase());
            }
        }

        return nextStep;
    }

    protected OnSuccessAction checkHintsAfterRestart() {
        return (dc) -> {
            LOGGER.info("[TEST] Insert {} documents", NUMBER_OF_DOC_PER_BATCH);
            executeESRequest(dc, (d, client) -> {
                JsonNode node = client.getDocuments(KEYSPACE, TABLE, "{}");
                assertTrue("Wrong number of Hits after insert", NUMBER_OF_DOC_PER_BATCH <= node.path("hits").path("total").intValue());
            });

            return shutdownTest(dc);
        };
    }
}