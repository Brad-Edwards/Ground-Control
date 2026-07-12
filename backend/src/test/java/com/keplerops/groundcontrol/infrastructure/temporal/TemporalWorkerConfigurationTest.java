package com.keplerops.groundcontrol.infrastructure.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.infrastructure.temporal.implement.ImplementWorkflowImpl;
import com.keplerops.groundcontrol.infrastructure.temporal.smoke.TemporalSmokeWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class TemporalWorkerConfigurationTest {

    @Test
    void propertiesApplyDefaultsAndTrimConfiguredValues() {
        var defaults = new TemporalWorkerProperties(false, null, " ", "\t");

        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.target()).isEqualTo("localhost:7233");
        assertThat(defaults.namespace()).isEqualTo("ground-control");
        assertThat(defaults.taskQueue()).isEqualTo("ground-control-implement");

        var configured = new TemporalWorkerProperties(true, " temporal:7233 ", " production ", " worker ");

        assertThat(configured.enabled()).isTrue();
        assertThat(configured.target()).isEqualTo("temporal:7233");
        assertThat(configured.namespace()).isEqualTo("production");
        assertThat(configured.taskQueue()).isEqualTo("worker");
    }

    @Test
    void serviceStubsUseConfiguredTarget() {
        var configuration = new TemporalWorkerConfiguration();
        var properties = new TemporalWorkerProperties(true, " temporal:7233 ", " production ", " worker ");
        var serviceStubs = configuration.temporalWorkflowServiceStubs(properties);

        try {
            assertThat(serviceStubs.getOptions().getTarget()).isEqualTo("temporal:7233");
        } finally {
            serviceStubs.shutdownNow();
            serviceStubs.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void workerFactoryRegistersSmokeWorkflowOnConfiguredTaskQueue() {
        var configuration = new TemporalWorkerConfiguration();
        var properties = new TemporalWorkerProperties(true, "unused:7233", "default", "config-smoke-test");
        WorkflowServiceStubs serviceStubs = mock(WorkflowServiceStubs.class);
        when(serviceStubs.getOptions()).thenReturn(WorkflowServiceStubsOptions.getDefaultInstance());
        WorkflowClient client = configuration.temporalWorkflowClient(serviceStubs, properties);
        WorkerFactory factory = mock(WorkerFactory.class);
        Worker worker = mock(Worker.class);

        when(factory.newWorker(properties.taskQueue())).thenReturn(worker);

        try (MockedStatic<WorkerFactory> factories = mockStatic(WorkerFactory.class)) {
            factories.when(() -> WorkerFactory.newInstance(client)).thenReturn(factory);

            assertThat(configuration.temporalWorkerFactory(client, properties)).isSameAs(factory);
        }

        assertThat(client.getOptions().getNamespace()).isEqualTo("default");
        verify(factory).newWorker(properties.taskQueue());
        verify(worker)
                .registerWorkflowImplementationTypes(TemporalSmokeWorkflowImpl.class, ImplementWorkflowImpl.class);
        verify(factory).start();
    }
}
