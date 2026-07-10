package com.keplerops.groundcontrol.infrastructure.temporal.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.workflowexecution.OperatorSignalType;
import com.keplerops.groundcontrol.domain.workflowexecution.RetryPhase;
import com.keplerops.groundcontrol.domain.workflowexecution.Reviewer;
import com.keplerops.groundcontrol.domain.workflowexecution.SignalDisposition;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionStatus;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowType;
import com.keplerops.groundcontrol.domain.workflowexecution.service.SendSignalCommand;
import com.keplerops.groundcontrol.domain.workflowexecution.service.StartWorkflowCommand;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CancelSignal;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CapDisposition;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementPhase;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementWorkflowInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.RetryFromSignal;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReviewCapDispositionSignal;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReviewerKind;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TemporalWorkflowControlAdapterTest {

    private static final String WORKFLOW_ID = "gc-implement-ground-control-1278";

    private final WorkflowClient client = mock(WorkflowClient.class);
    private final TemporalControlProperties properties =
            new TemporalControlProperties(true, "ground-control-implement", "make check");
    private final TemporalWorkflowControlAdapter adapter = new TemporalWorkflowControlAdapter(client, properties);

    private static WorkflowExecution execution(String workflowId, String runId) {
        return WorkflowExecution.newBuilder()
                .setWorkflowId(workflowId)
                .setRunId(runId)
                .build();
    }

    @Test
    void startBuildsInputAndOptionsAndReturnsRef() {
        WorkflowStub stub = mock(WorkflowStub.class);
        var optionsCaptor = ArgumentCaptor.forClass(WorkflowOptions.class);
        when(client.newUntypedWorkflowStub(eq("ImplementWorkflow"), optionsCaptor.capture()))
                .thenReturn(stub);
        var inputCaptor = ArgumentCaptor.forClass(ImplementWorkflowInput.class);
        when(stub.start(inputCaptor.capture())).thenReturn(execution(WORKFLOW_ID, "run-1"));

        var command = new StartWorkflowCommand(
                WORKFLOW_ID, WorkflowType.IMPLEMENT, "ground-control", 1278, "sonar-key", 3, List.of("GC-O009"), 300);
        var ref = adapter.start(command);

        assertThat(ref.workflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(ref.runId()).isEqualTo("run-1");
        assertThat(ref.workflowType()).isEqualTo(WorkflowType.IMPLEMENT);

        var options = optionsCaptor.getValue();
        assertThat(options.getWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(options.getTaskQueue()).isEqualTo("ground-control-implement");
        assertThat(options.getMemo()).containsEntry("project", "ground-control").containsEntry("issueNumber", 1278);

        var input = inputCaptor.getValue();
        // Blank completionCommand falls back to the configured default.
        assertThat(input.completionCommand()).isEqualTo("make check");
        assertThat(input.issueNumber()).isEqualTo(1278);
        assertThat(input.requirementUids()).containsExactly("GC-O009");
    }

    @Test
    void startTranslatesAlreadyStartedToConflict() {
        WorkflowStub stub = mock(WorkflowStub.class);
        when(client.newUntypedWorkflowStub(anyString(), any(WorkflowOptions.class)))
                .thenReturn(stub);
        when(stub.start(any()))
                .thenThrow(new WorkflowExecutionAlreadyStarted(
                        execution(WORKFLOW_ID, "run-1"), "ImplementWorkflow", null));

        var command = new StartWorkflowCommand(
                WORKFLOW_ID, WorkflowType.IMPLEMENT, "ground-control", 1278, null, null, List.of(), null);
        assertThatThrownBy(() -> adapter.start(command)).isInstanceOf(ConflictException.class);
    }

    @Test
    void signalCancelSendsNamedCancelSignal() {
        WorkflowStub stub = mock(WorkflowStub.class);
        when(client.newUntypedWorkflowStub(WORKFLOW_ID)).thenReturn(stub);

        adapter.signal(WORKFLOW_ID, new SendSignalCommand(OperatorSignalType.CANCEL, "stop it", null, null, null));

        var payload = ArgumentCaptor.forClass(Object.class);
        verify(stub).signal(eq("cancel"), payload.capture());
        assertThat(payload.getValue()).isEqualTo(new CancelSignal("stop it"));
    }

    @Test
    void signalRetryFromMapsPhase() {
        WorkflowStub stub = mock(WorkflowStub.class);
        when(client.newUntypedWorkflowStub(WORKFLOW_ID)).thenReturn(stub);

        adapter.signal(
                WORKFLOW_ID,
                new SendSignalCommand(OperatorSignalType.RETRY_FROM, "retry", RetryPhase.D_SHIP_PIPELINE, null, null));

        var payload = ArgumentCaptor.forClass(Object.class);
        verify(stub).signal(eq("retryFrom"), payload.capture());
        assertThat(payload.getValue()).isEqualTo(new RetryFromSignal(ImplementPhase.D_SHIP_PIPELINE, "retry"));
    }

    @Test
    void signalReviewCapMapsReviewerAndDisposition() {
        WorkflowStub stub = mock(WorkflowStub.class);
        when(client.newUntypedWorkflowStub(WORKFLOW_ID)).thenReturn(stub);

        adapter.signal(
                WORKFLOW_ID,
                new SendSignalCommand(
                        OperatorSignalType.REVIEW_CAP_DISPOSITION,
                        null,
                        null,
                        Reviewer.TEST_QUALITY,
                        SignalDisposition.ONE_MORE_CYCLE));

        var payload = ArgumentCaptor.forClass(Object.class);
        verify(stub).signal(eq("applyReviewCapDisposition"), payload.capture());
        assertThat(payload.getValue())
                .isEqualTo(new ReviewCapDispositionSignal(ReviewerKind.TEST_QUALITY, CapDisposition.ONE_MORE_CYCLE));
    }

    @Test
    void describeMapsMetadataToView() {
        WorkflowStub stub = mock(WorkflowStub.class);
        when(client.newUntypedWorkflowStub(WORKFLOW_ID)).thenReturn(stub);
        WorkflowExecutionDescription desc = mock(WorkflowExecutionDescription.class);
        when(stub.describe()).thenReturn(desc);
        when(desc.getExecution()).thenReturn(execution(WORKFLOW_ID, "run-1"));
        when(desc.getWorkflowType()).thenReturn("ImplementWorkflow");
        when(desc.getStatus())
                .thenReturn(io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
        when(desc.getStartTime()).thenReturn(Instant.parse("2026-07-08T00:00:00Z"));
        when(desc.getHistoryLength()).thenReturn(7L);
        when(desc.getMemo("project", String.class)).thenReturn("ground-control");
        when(desc.getMemo("issueNumber", Integer.class)).thenReturn(1278);
        when(desc.getMemo("requirementUids", List.class)).thenReturn(List.of("GC-O009"));

        var view = adapter.describe(WORKFLOW_ID).orElseThrow();
        assertThat(view.workflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(view.status()).isEqualTo(WorkflowExecutionStatus.RUNNING);
        assertThat(view.historyLength()).isEqualTo(7L);
        assertThat(view.correlation().project()).isEqualTo("ground-control");
        assertThat(view.correlation().issueNumber()).isEqualTo(1278);
        assertThat(view.correlation().requirementUids()).containsExactly("GC-O009");
    }

    @Test
    void describeReturnsEmptyWhenNotFound() {
        WorkflowStub stub = mock(WorkflowStub.class);
        when(client.newUntypedWorkflowStub(WORKFLOW_ID)).thenReturn(stub);
        when(stub.describe())
                .thenThrow(new WorkflowNotFoundException(execution(WORKFLOW_ID, "run-1"), "ImplementWorkflow", null));

        assertThat(adapter.describe(WORKFLOW_ID)).isEmpty();
    }

    @Test
    void listForProjectFiltersToExactlyOwnedExecutions() {
        WorkflowExecutionMetadata mine = mock(WorkflowExecutionMetadata.class);
        when(mine.getExecution()).thenReturn(execution(WORKFLOW_ID, "run-1"));
        when(mine.getStatus())
                .thenReturn(io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
        WorkflowExecutionMetadata other = mock(WorkflowExecutionMetadata.class);
        when(other.getExecution()).thenReturn(execution("gc-implement-other-project-9", "run-2"));
        // A neighbouring project whose id shares the prefix but is NOT owned by "ground-control":
        // a raw startsWith filter would leak it; the belongsToProject digit-suffix predicate excludes it.
        WorkflowExecutionMetadata neighbour = mock(WorkflowExecutionMetadata.class);
        when(neighbour.getExecution()).thenReturn(execution("gc-implement-ground-control-x-1", "run-3"));
        when(client.listExecutions(anyString())).thenReturn(Stream.of(mine, other, neighbour));

        var views = adapter.listForProject("ground-control", 50);
        assertThat(views).hasSize(1);
        assertThat(views.get(0).workflowId()).isEqualTo(WORKFLOW_ID);
    }

    @Test
    void signalTranslatesClosedWorkflowRaceToNotFound() {
        WorkflowStub stub = mock(WorkflowStub.class);
        when(client.newUntypedWorkflowStub(WORKFLOW_ID)).thenReturn(stub);
        org.mockito.Mockito.doThrow(
                        new WorkflowNotFoundException(execution(WORKFLOW_ID, "run-1"), "ImplementWorkflow", null))
                .when(stub)
                .signal(eq("cancel"), org.mockito.ArgumentMatchers.<Object>any());

        assertThatThrownBy(() -> adapter.signal(
                        WORKFLOW_ID, new SendSignalCommand(OperatorSignalType.CANCEL, "stop", null, null, null)))
                .isInstanceOf(com.keplerops.groundcontrol.domain.exception.NotFoundException.class);
    }

    @Test
    void mapStatusCoversEveryTemporalStatus() {
        var e = io.temporal.api.enums.v1.WorkflowExecutionStatus.class.getEnumConstants();
        for (var status : e) {
            // Never throws and never returns null for any server-side status, including UNRECOGNIZED.
            assertThat(TemporalWorkflowControlAdapter.mapStatus(status)).isNotNull();
        }
        assertThat(TemporalWorkflowControlAdapter.mapStatus(
                        io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT))
                .isEqualTo(WorkflowExecutionStatus.TIMED_OUT);
        assertThat(TemporalWorkflowControlAdapter.mapStatus(null)).isEqualTo(WorkflowExecutionStatus.UNKNOWN);
    }

    @Test
    void enumMappingsAreExhaustive() {
        for (var phase : RetryPhase.values()) {
            assertThat(TemporalWorkflowControlAdapter.toImplementPhase(phase).name())
                    .isEqualTo(phase.name());
        }
        for (var reviewer : Reviewer.values()) {
            assertThat(TemporalWorkflowControlAdapter.toReviewerKind(reviewer).name())
                    .isEqualTo(reviewer.name());
        }
        for (var disposition : SignalDisposition.values()) {
            assertThat(TemporalWorkflowControlAdapter.toCapDisposition(disposition)
                            .name())
                    .isEqualTo(disposition.name());
        }
    }
}
