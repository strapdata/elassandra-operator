package com.strapdata.strapkop.plugins;

import com.strapdata.strapkop.dns.DnsConfiguration;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.task.*;
import com.strapdata.strapkop.plugins.test.TestSuiteExecutor;
import com.strapdata.strapkop.plugins.test.step.Step;
import com.strapdata.strapkop.plugins.test.step.StepFailedException;
import com.strapdata.strapkop.reconcilier.TaskReconcilier;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1ObjectMeta;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.reactivex.Completable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TestTestSuitePlugin {

    private final String CLUSTER = "cluster1";
    private final String DATACENTER = "dc1";

    private TestSuitePlugin plugin;

    private OperatorConfig opConfigMock = mock(OperatorConfig.class);
    private DnsConfiguration dnsConfigMock = mock(DnsConfiguration.class);

    private ApplicationContext contextMock = mock(ApplicationContext.class);
    private K8sResourceUtils k8sResourceUtilsMock = mock(K8sResourceUtils.class);
    private AuthorityManager authorityManagerMock = mock(AuthorityManager.class);
    private CoreV1Api coreApiMock = mock(CoreV1Api.class);
    private AppsV1Api appsApiMock = mock(AppsV1Api.class);
    private MeterRegistry meterRegistry = mock(MeterRegistry.class);

    @BeforeEach
    public void initTest() throws Exception {
        plugin = new TestSuitePlugin(contextMock, k8sResourceUtilsMock, authorityManagerMock, coreApiMock, appsApiMock, opConfigMock, dnsConfigMock, meterRegistry);
        when(k8sResourceUtilsMock.updateTaskStatus(any())).thenReturn(Completable.complete());
        when(contextMock.getBean(FakeExecutor.class)).thenReturn(new FakeExecutor());
    }

    @Test
    public void testIsAvailable() {
        activateTestPlugin(true);

        Task task = createNewTask("test-001");
        assertFalse(plugin.isBusy(task));
    }

    @Test
    public void testIsRunning() {
        activateTestPlugin(true);

        Task task = createNewTask("test-001");
        assertFalse(plugin.isRunning(task));
    }

    @Test
    public void testStart_TestEnabled() throws InterruptedException {
        activateTestPlugin(true);

        Task task = createNewTask("test-001");
        assertFalse(plugin.isBusy(task));
        assertFalse(plugin.isRunning(task));

        Completable complatable = plugin.initialize(new TaskReconcilier.TaskWrapper(task), createFakeDc());
        complatable.blockingGet();

        assertTrue(plugin.isRunning(task));
        assertTrue(plugin.isBusy(task));

        Task task2 = createNewTask("test-002");
        assertTrue(plugin.isBusy(task2));
        assertFalse(plugin.isRunning(task2));

        // wait the task timeout
        Thread.sleep(3000);

        assertFalse(plugin.isBusy(task2));
        assertFalse(plugin.isRunning(task2));
        assertEquals(TaskPhase.FAILED, task.getStatus().getPhase());
        assertTrue(task.getStatus().getLastMessage().startsWith("Test Timeout"));
    }

    @Test
    public void testStart_TestDisabled() {
        activateTestPlugin(false);

        Task task = createNewTask("test-001");
        assertFalse(plugin.isBusy(task));
        assertFalse(plugin.isRunning(task));

        Completable complatable = plugin.initialize(new TaskReconcilier.TaskWrapper(task), createFakeDc());
        complatable.blockingGet();

        assertFalse(plugin.isBusy(task));
        assertFalse(plugin.isRunning(task));
    }

    @Test
    public void testStart_ExecTestSuite() throws Exception {
        activateTestPlugin(true);
        DataCenter dc = createFakeDc();
        dc.getStatus().setPhase(DataCenterPhase.CREATING);

        Task task = createNewTask("test-001");
        assertFalse(plugin.isBusy(task));
        assertFalse(plugin.isRunning(task));

        Completable complatable = plugin.initialize(new TaskReconcilier.TaskWrapper(task), new DataCenter());
        complatable.blockingGet();

        assertTrue(plugin.isRunning(task));
        assertTrue(plugin.isBusy(task));

        dc.getStatus().setPhase(DataCenterPhase.SCALING_UP);// first step
        plugin.reconcile(dc).blockingGet();

        // test still running
        assertTrue(plugin.isRunning(task));
        assertTrue(plugin.isBusy(task));

        dc.getStatus().setPhase(DataCenterPhase.RUNNING);// first step
        plugin.reconcile(dc).blockingGet();

        assertFalse(plugin.isBusy(task));
        assertFalse(plugin.isRunning(task));

        assertEquals(TaskPhase.SUCCEED, task.getStatus().getPhase());
        assertEquals("Test OK. (2 steps passed)", task.getStatus().getLastMessage());
    }

    private DataCenter createFakeDc() {
        DataCenter dc = new DataCenter();
        dc.setMetadata(new V1ObjectMeta());
        dc.getMetadata().setNamespace("default");
        return dc;
    }

    private Task createNewTask(String name) {
        return createNewTask(name, 2000);
    }

    private Task createNewTask(String name, int timeout) {
        V1ObjectMeta v1ObjectMeta = new V1ObjectMeta();
        v1ObjectMeta.setName(name);
        return new Task()
                .setMetadata(v1ObjectMeta)
                .setSpec(new TaskSpec()
                        .setCluster(CLUSTER)
                        .setDatacenter(DATACENTER)
                        .setTest(new TestTaskSpec()
                                .setTimeOut(timeout)
                                .setTestSuite(FakeExecutor.class.getName())))
                .setStatus(new TaskStatus().
                        setPhase(TaskPhase.RUNNING));
    }

    private void activateTestPlugin(boolean b) {
        OperatorConfig.TestSuiteConfig testSuiteConfig = mock(OperatorConfig.TestSuiteConfig.class);
        when(testSuiteConfig.isEnabled()).thenReturn(b);
        when(testSuiteConfig.getPlatform()).thenReturn(OperatorConfig.TestSuiteConfig.Platform.LOCAL);
        when(opConfigMock.getTest()).thenReturn(testSuiteConfig);
    }

    public class FakeExecutor extends TestSuiteExecutor {

        @Override
        protected Step initialStep() {
            return this::checkScaleUp;
        }

        protected Step checkScaleUp (DataCenter dc) throws StepFailedException {
            LOGGER.info("[TEST] execute checkScaleUp");
            if (Objects.equals(dc.getStatus().getPhase(), DataCenterPhase.SCALING_UP)) {
                return this::checkRunning;
            } else {
                return this::checkScaleUp;
            }
        }

        protected Step checkRunning (DataCenter dc) throws StepFailedException {
            LOGGER.info("[TEST] execute checkRunning");
            if (Objects.equals(dc.getStatus().getPhase(), DataCenterPhase.RUNNING)) {
                return shutdownTest(dc);
            } else {
                return this::checkRunning;
            }
        }
    }

}
