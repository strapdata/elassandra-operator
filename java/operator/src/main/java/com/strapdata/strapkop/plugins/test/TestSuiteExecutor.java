package com.strapdata.strapkop.plugins.test;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.k8s.K8sResourceTestUtils;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.plugins.test.step.Step;
import com.strapdata.strapkop.plugins.test.step.StepFailedException;
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
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

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
}
