package com.keplerops.groundcontrol.infrastructure.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.infrastructure.temporal.smoke.TemporalSmokeWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerFactory;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

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
        }
    }

    @Test
    void workerFactoryRegistersSmokeWorkflowOnConfiguredTaskQueue() throws Exception {
        var configuration = new TemporalWorkerConfiguration();
        var properties = new TemporalWorkerProperties(true, "unused:7233", "default", "config-smoke-test");

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            var client = configuration.temporalWorkflowClient(environment.getWorkflowServiceStubs(), properties);
            assertThat(client.getOptions().getNamespace()).isEqualTo("default");

            WorkerFactory factory = configuration.temporalWorkerFactory(client, properties);
            try {
                var workflow = client.newWorkflowStub(
                        TemporalSmokeWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setTaskQueue(properties.taskQueue())
                                .setWorkflowId("gc-config-smoke-" + UUID.randomUUID())
                                .build());

                WorkflowClient.start(workflow::run, "ground-control");
                workflow.complete("project-partitioned");

                assertThat(WorkflowStub.fromTyped(workflow).getResult(10, TimeUnit.SECONDS, String.class))
                        .isEqualTo("ground-control:project-partitioned");
            } finally {
                factory.shutdownNow();
            }
        }
    }
}
