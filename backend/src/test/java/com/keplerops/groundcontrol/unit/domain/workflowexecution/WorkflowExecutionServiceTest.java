package com.keplerops.groundcontrol.unit.domain.workflowexecution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.workflowexecution.OperatorSignalType;
import com.keplerops.groundcontrol.domain.workflowexecution.Reviewer;
import com.keplerops.groundcontrol.domain.workflowexecution.SignalDisposition;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowControlPort;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionRef;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionStatus;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionView;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowType;
import com.keplerops.groundcontrol.domain.workflowexecution.service.SendSignalCommand;
import com.keplerops.groundcontrol.domain.workflowexecution.service.StartWorkflowCommand;
import com.keplerops.groundcontrol.domain.workflowexecution.service.WorkflowExecutionService;
import com.keplerops.groundcontrol.domain.workflowexecution.service.WorkflowExecutionService.SignalRequest;
import com.keplerops.groundcontrol.domain.workflowexecution.service.WorkflowExecutionService.StartRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

@SuppressWarnings("unchecked")
class WorkflowExecutionServiceTest {

    private static final String PROJECT = "ground-control";
    private static final String WORKFLOW_ID = "gc-implement-ground-control-1278";

    private final ObjectProvider<WorkflowControlPort> portProvider = org.mockito.Mockito.mock(ObjectProvider.class);
    private final WorkflowControlPort port = org.mockito.Mockito.mock(WorkflowControlPort.class);
    private final ProjectService projectService = org.mockito.Mockito.mock(ProjectService.class);
    private final WorkflowExecutionService service = new WorkflowExecutionService(portProvider, projectService);

    @BeforeEach
    void setUp() {
        when(projectService.requireProjectIdentifier(any())).thenReturn(PROJECT);
        when(portProvider.getIfAvailable()).thenReturn(port);
    }

    @Test
    void startResolvesProjectAndDelegatesWithProjectScopedWorkflowId() {
        when(port.start(any()))
                .thenReturn(new WorkflowExecutionRef(WORKFLOW_ID, "run-1", WorkflowType.IMPLEMENT, PROJECT));

        var ref = service.start(
                PROJECT, new StartRequest(WorkflowType.IMPLEMENT, 1278, "sonar-key", 3, List.of("GC-O009"), 300));

        var captor = ArgumentCaptor.forClass(StartWorkflowCommand.class);
        verify(port).start(captor.capture());
        var command = captor.getValue();
        assertThat(command.workflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(command.project()).isEqualTo(PROJECT);
        assertThat(command.issueNumber()).isEqualTo(1278);
        assertThat(command.requirementUids()).containsExactly("GC-O009");
        assertThat(ref.workflowId()).isEqualTo(WORKFLOW_ID);
    }

    @Test
    void startRejectsNullWorkflowType() {
        assertThatThrownBy(() -> service.start(PROJECT, new StartRequest(null, 1278, null, null, null, null)))
                .isInstanceOf(DomainValidationException.class);
        verify(port, never()).start(any());
    }

    @Test
    void startRejectsNonPositiveIssueNumber() {
        assertThatThrownBy(() ->
                        service.start(PROJECT, new StartRequest(WorkflowType.IMPLEMENT, 0, null, null, null, null)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void reportsServiceUnavailableWhenControlPortAbsent() {
        when(portProvider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> service.list(PROJECT, 10)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void listScopesToProjectPrefixAndClampsLimit() {
        when(port.listForProject(any(), anyInt())).thenReturn(List.of());
        service.list(PROJECT, 5000);
        verify(port).listForProject(eq(PROJECT), eq(200));
    }

    @Test
    void listDefaultsLimitWhenNull() {
        when(port.listForProject(any(), anyInt())).thenReturn(List.of());
        service.list(PROJECT, null);
        verify(port).listForProject(eq(PROJECT), eq(50));
    }

    @Test
    void getReturnsViewForOwnedExecution() {
        var view = sampleView();
        when(port.describe(WORKFLOW_ID)).thenReturn(Optional.of(view));
        assertThat(service.get(PROJECT, WORKFLOW_ID)).isEqualTo(view);
    }

    @Test
    void getRejectsCrossProjectIdAsNotFoundWithoutTouchingPort() {
        assertThatThrownBy(() -> service.get(PROJECT, "gc-implement-other-project-1"))
                .isInstanceOf(NotFoundException.class);
        verify(port, never()).describe(any());
    }

    @Test
    void getReturnsNotFoundWhenExecutionMissing() {
        when(port.describe(WORKFLOW_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(PROJECT, WORKFLOW_ID)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void signalCancelRequiresReason() {
        assertThatThrownBy(() -> service.signal(
                        PROJECT, WORKFLOW_ID, new SignalRequest(OperatorSignalType.CANCEL, "  ", null, null, null)))
                .isInstanceOf(DomainValidationException.class);
        verify(port, never()).signal(any(), any());
    }

    @Test
    void signalRetryFromRequiresPhase() {
        assertThatThrownBy(() -> service.signal(
                        PROJECT, WORKFLOW_ID, new SignalRequest(OperatorSignalType.RETRY_FROM, null, null, null, null)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void signalReviewCapRequiresReviewerAndDisposition() {
        assertThatThrownBy(() -> service.signal(
                        PROJECT,
                        WORKFLOW_ID,
                        new SignalRequest(OperatorSignalType.REVIEW_CAP_DISPOSITION, null, null, Reviewer.CODEX, null)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void signalRejectsCrossProjectIdAsNotFound() {
        assertThatThrownBy(() -> service.signal(
                        PROJECT,
                        "gc-implement-other-project-1",
                        new SignalRequest(OperatorSignalType.CANCEL, "stop", null, null, null)))
                .isInstanceOf(NotFoundException.class);
        verify(port, never()).signal(any(), any());
    }

    @Test
    void signalReturnsNotFoundWhenExecutionMissing() {
        when(port.describe(WORKFLOW_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.signal(
                        PROJECT, WORKFLOW_ID, new SignalRequest(OperatorSignalType.CANCEL, "stop", null, null, null)))
                .isInstanceOf(NotFoundException.class);
        verify(port, never()).signal(any(), any());
    }

    @Test
    void signalRejectsNonSignalableExecution() {
        var completed = new WorkflowExecutionView(
                WORKFLOW_ID,
                "run-1",
                WorkflowType.IMPLEMENT,
                WorkflowExecutionStatus.COMPLETED,
                null,
                null,
                0L,
                new WorkflowExecutionView.Correlation(PROJECT, 1278, List.of()));
        when(port.describe(WORKFLOW_ID)).thenReturn(Optional.of(completed));
        assertThatThrownBy(() -> service.signal(
                        PROJECT, WORKFLOW_ID, new SignalRequest(OperatorSignalType.CANCEL, "stop", null, null, null)))
                .isInstanceOf(DomainValidationException.class);
        verify(port, never()).signal(any(), any());
    }

    @Test
    void signalDelegatesMappedCommandForExistingExecution() {
        when(port.describe(WORKFLOW_ID)).thenReturn(Optional.of(sampleView()));
        service.signal(
                PROJECT,
                WORKFLOW_ID,
                new SignalRequest(
                        OperatorSignalType.REVIEW_CAP_DISPOSITION,
                        null,
                        null,
                        Reviewer.TEST_QUALITY,
                        SignalDisposition.ESCALATE_TO_HUMAN));

        var captor = ArgumentCaptor.forClass(SendSignalCommand.class);
        verify(port).signal(eq(WORKFLOW_ID), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(OperatorSignalType.REVIEW_CAP_DISPOSITION);
        assertThat(captor.getValue().reviewer()).isEqualTo(Reviewer.TEST_QUALITY);
        assertThat(captor.getValue().disposition()).isEqualTo(SignalDisposition.ESCALATE_TO_HUMAN);
    }

    @Test
    void signalRequiresSignalType() {
        assertThatThrownBy(() -> service.signal(PROJECT, WORKFLOW_ID, new SignalRequest(null, null, null, null, null)))
                .isInstanceOf(DomainValidationException.class);
    }

    private static WorkflowExecutionView sampleView() {
        return new WorkflowExecutionView(
                WORKFLOW_ID,
                "run-1",
                WorkflowType.IMPLEMENT,
                WorkflowExecutionStatus.RUNNING,
                null,
                null,
                0L,
                new WorkflowExecutionView.Correlation(PROJECT, 1278, List.of("GC-O009")));
    }
}
