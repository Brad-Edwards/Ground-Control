package com.keplerops.groundcontrol.infrastructure.temporal.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowType;
import com.keplerops.groundcontrol.domain.workflowexecution.service.StartWorkflowCommand;
import com.keplerops.groundcontrol.infrastructure.temporal.TemporalWorkerProperties;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TemporalControlConfigurationTest {

    private final TemporalControlConfiguration configuration = new TemporalControlConfiguration();

    private WorkflowOptions captureStartOptions(WorkflowClient client, TemporalControlProperties controlProps) {
        var worker = new TemporalWorkerProperties(true, null, null, "customized-worker-queue");
        var adapter = configuration.temporalWorkflowControlAdapter(client, controlProps, worker);

        WorkflowStub stub = mock(WorkflowStub.class);
        var optionsCaptor = ArgumentCaptor.forClass(WorkflowOptions.class);
        when(client.newUntypedWorkflowStub(anyString(), optionsCaptor.capture()))
                .thenReturn(stub);
        when(stub.start(org.mockito.ArgumentMatchers.<Object>any()))
                .thenReturn(WorkflowExecution.newBuilder()
                        .setWorkflowId("gc-implement-p-1")
                        .setRunId("r1")
                        .build());

        adapter.start(new StartWorkflowCommand(
                "gc-implement-p-1", WorkflowType.IMPLEMENT, "p", 1, null, null, List.of(), null));
        return optionsCaptor.getValue();
    }

    @Test
    void controlSurfaceFallsBackToWorkerQueueWhenUnset() {
        var options = captureStartOptions(
                mock(WorkflowClient.class), new TemporalControlProperties(true, null, "make check"));
        assertThat(options.getTaskQueue()).isEqualTo("customized-worker-queue");
    }

    @Test
    void controlSurfaceHonoursExplicitQueueOverride() {
        var options = captureStartOptions(
                mock(WorkflowClient.class), new TemporalControlProperties(true, "control-only-queue", "make check"));
        assertThat(options.getTaskQueue()).isEqualTo("control-only-queue");
    }
}
