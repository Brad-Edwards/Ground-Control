package com.keplerops.groundcontrol.unit.infrastructure.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.keplerops.groundcontrol.infrastructure.temporal.smoke.TemporalSmokeWorkflow;
import com.keplerops.groundcontrol.infrastructure.temporal.smoke.TemporalSmokeWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TemporalSmokeWorkflowTest {

    private static final String TASK_QUEUE = "ground-control-smoke-test";

    @Test
    void smokeWorkflowCompletesAfterWorkerFactoryRestart() throws Exception {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker firstWorker = environment.newWorker(TASK_QUEUE);
            firstWorker.registerWorkflowImplementationTypes(TemporalSmokeWorkflowImpl.class);
            environment.start();

            WorkflowClient client = environment.getWorkflowClient();
            String workflowId = "gc-smoke-" + UUID.randomUUID();
            TemporalSmokeWorkflow workflow = client.newWorkflowStub(
                    TemporalSmokeWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setTaskQueue(TASK_QUEUE)
                            .setWorkflowId(workflowId)
                            .build());

            WorkflowClient.start(workflow::run, "ground-control");
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(workflow.isWaiting()).isTrue());

            firstWorker.suspendPolling();

            WorkerFactory restartedFactory = WorkerFactory.newInstance(client);
            try {
                Worker restartedWorker = restartedFactory.newWorker(TASK_QUEUE);
                restartedWorker.registerWorkflowImplementationTypes(TemporalSmokeWorkflowImpl.class);
                restartedFactory.start();

                TemporalSmokeWorkflow resumed = client.newWorkflowStub(TemporalSmokeWorkflow.class, workflowId);
                resumed.complete("project-partitioned");

                assertThat(WorkflowStub.fromTyped(resumed).getResult(10, TimeUnit.SECONDS, String.class))
                        .isEqualTo("ground-control:project-partitioned");
            } finally {
                restartedFactory.shutdownNow();
            }
        }
    }
}
