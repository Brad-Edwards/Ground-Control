package com.keplerops.groundcontrol.unit.domain.workflowexecution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.exception.AuthorizationException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.domain.llm.ResolvedLlmRoute;
import com.keplerops.groundcontrol.domain.llm.TrustedRouteResolver;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.workflowexecution.OperatorSignalContract;
import com.keplerops.groundcontrol.domain.workflowexecution.OperatorSignalType;
import com.keplerops.groundcontrol.domain.workflowexecution.Reviewer;
import com.keplerops.groundcontrol.domain.workflowexecution.SignalDisposition;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowControlPort;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionRef;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionStatus;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionView;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowType;
import com.keplerops.groundcontrol.domain.workflowexecution.audit.AuthorizationOutcome;
import com.keplerops.groundcontrol.domain.workflowexecution.audit.OperatorSignalAuditRecorder;
import com.keplerops.groundcontrol.domain.workflowexecution.service.SendSignalCommand;
import com.keplerops.groundcontrol.domain.workflowexecution.service.StartWorkflowCommand;
import com.keplerops.groundcontrol.domain.workflowexecution.service.WorkflowExecutionService;
import com.keplerops.groundcontrol.domain.workflowexecution.service.WorkflowExecutionService.SignalRequest;
import com.keplerops.groundcontrol.domain.workflowexecution.service.WorkflowExecutionService.StartRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

@SuppressWarnings("unchecked")
class WorkflowExecutionServiceTest {

    private static final String PROJECT = "ground-control";
    private static final String WORKFLOW_ID = "gc-implement-ground-control-1278";
    private static final String ACTOR = "operator-admin";

    private static final String PLANNING_STAGE = "planning";

    private final ObjectProvider<WorkflowControlPort> portProvider = mock(ObjectProvider.class);
    private final WorkflowControlPort port = mock(WorkflowControlPort.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final OperatorSignalAuditRecorder auditRecorder = mock(OperatorSignalAuditRecorder.class);
    private final TrustedRouteResolver trustedRouteResolver = mock(TrustedRouteResolver.class);
    private final WorkflowExecutionService service =
            new WorkflowExecutionService(portProvider, projectService, auditRecorder, trustedRouteResolver);

    @BeforeEach
    void setUp() {
        when(projectService.requireProjectIdentifier(any())).thenReturn(PROJECT);
        when(portProvider.getIfAvailable()).thenReturn(port);
        when(trustedRouteResolver.resolve(any(), any())).thenReturn(sampleRoute());
        // Default to an authorized actor; individual authority tests override this.
        ActorHolder.set(ACTOR);
    }

    private static ResolvedLlmRoute sampleRoute() {
        return new ResolvedLlmRoute("v2", PROJECT, PLANNING_STAGE, "high", "anthropic", "claude-opus-4-8", "digest-1");
    }

    @AfterEach
    void tearDown() {
        ActorHolder.clear();
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
        assertThat(command.route()).isEqualTo(sampleRoute());
        assertThat(ref.workflowId()).isEqualTo(WORKFLOW_ID);
    }

    @Test
    void startResolvesTheRouteForThePlanningStageBeforeDelegatingToThePort() {
        when(port.start(any()))
                .thenReturn(new WorkflowExecutionRef(WORKFLOW_ID, "run-1", WorkflowType.IMPLEMENT, PROJECT));

        service.start(PROJECT, new StartRequest(WorkflowType.IMPLEMENT, 1278, null, null, null, null));

        verify(trustedRouteResolver).resolve(PROJECT, PLANNING_STAGE);
    }

    @Test
    void startFailsClosedWhenRouteResolutionIsUnavailableAndNeverTouchesThePort() {
        when(trustedRouteResolver.resolve(any(), any()))
                .thenThrow(new ServiceUnavailableException("route resolution bridge unavailable"));

        assertThatThrownBy(() ->
                        service.start(PROJECT, new StartRequest(WorkflowType.IMPLEMENT, 1278, null, null, null, null)))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(port, never()).start(any());
    }

    @Test
    void startFailsClosedWhenRouteResolutionRejectsTheRouteAsInvalid() {
        when(trustedRouteResolver.resolve(any(), any())).thenThrow(new DomainValidationException("unknown provider"));

        assertThatThrownBy(() ->
                        service.start(PROJECT, new StartRequest(WorkflowType.IMPLEMENT, 1278, null, null, null, null)))
                .isInstanceOf(DomainValidationException.class);
        verify(port, never()).start(any());
    }

    @Test
    void startRejectsNullWorkflowType() {
        var request = new StartRequest(null, 1278, null, null, null, null);
        assertThatThrownBy(() -> service.start(PROJECT, request)).isInstanceOf(DomainValidationException.class);
        verify(port, never()).start(any());
    }

    @Test
    void startRejectsNonPositiveIssueNumber() {
        var request = new StartRequest(WorkflowType.IMPLEMENT, 0, null, null, null, null);
        assertThatThrownBy(() -> service.start(PROJECT, request)).isInstanceOf(DomainValidationException.class);
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
        verify(port).listForProject(PROJECT, 200);
    }

    @Test
    void listDefaultsLimitWhenNull() {
        when(port.listForProject(any(), anyInt())).thenReturn(List.of());
        service.list(PROJECT, null);
        verify(port).listForProject(PROJECT, 50);
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
        var request = new SignalRequest(OperatorSignalType.CANCEL, "  ", null, null, null);
        assertThatThrownBy(() -> service.signal(PROJECT, WORKFLOW_ID, request))
                .isInstanceOf(DomainValidationException.class);
        verify(port, never()).signal(any(), any());
    }

    @Test
    void signalRetryFromRequiresPhase() {
        var request = new SignalRequest(OperatorSignalType.RETRY_FROM, null, null, null, null);
        assertThatThrownBy(() -> service.signal(PROJECT, WORKFLOW_ID, request))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void signalReviewCapRequiresReviewerAndDisposition() {
        var request = new SignalRequest(OperatorSignalType.REVIEW_CAP_DISPOSITION, null, null, Reviewer.CODEX, null);
        assertThatThrownBy(() -> service.signal(PROJECT, WORKFLOW_ID, request))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void signalRejectsCrossProjectIdAsNotFound() {
        var request = new SignalRequest(OperatorSignalType.CANCEL, "stop", null, null, null);
        assertThatThrownBy(() -> service.signal(PROJECT, "gc-implement-other-project-1", request))
                .isInstanceOf(NotFoundException.class);
        verify(port, never()).signal(any(), any());
    }

    @Test
    void signalReturnsNotFoundWhenExecutionMissing() {
        when(port.describe(WORKFLOW_ID)).thenReturn(Optional.empty());
        var request = new SignalRequest(OperatorSignalType.CANCEL, "stop", null, null, null);
        assertThatThrownBy(() -> service.signal(PROJECT, WORKFLOW_ID, request)).isInstanceOf(NotFoundException.class);
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
                new WorkflowExecutionView.Correlation(PROJECT, 1278, List.of()),
                null);
        when(port.describe(WORKFLOW_ID)).thenReturn(Optional.of(completed));
        var request = new SignalRequest(OperatorSignalType.CANCEL, "stop", null, null, null);
        assertThatThrownBy(() -> service.signal(PROJECT, WORKFLOW_ID, request))
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
        var request = new SignalRequest(null, null, null, null, null);
        assertThatThrownBy(() -> service.signal(PROJECT, WORKFLOW_ID, request))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void signalRecordsAllowedAuditWithRunIdForDeliveredSignal() {
        when(port.describe(WORKFLOW_ID)).thenReturn(Optional.of(sampleView()));
        var request = new SignalRequest(OperatorSignalType.CANCEL, "stop", null, null, null);

        service.signal(PROJECT, WORKFLOW_ID, request);

        var captor = ArgumentCaptor.forClass(SendSignalCommand.class);
        verify(port).signal(eq(WORKFLOW_ID), any());
        verify(auditRecorder)
                .write(
                        eq(ACTOR),
                        eq(PROJECT),
                        eq(WORKFLOW_ID),
                        eq("run-1"),
                        captor.capture(),
                        eq(AuthorizationOutcome.ALLOWED));
        assertThat(captor.getValue().type()).isEqualTo(OperatorSignalType.CANCEL);
    }

    @Test
    void signalDeniesUnauthenticatedActorRecordsDeniedAuditAndDoesNotTouchPort() {
        ActorHolder.set("anonymous"); // ActorFilter's default for an unauthenticated request.
        var request = new SignalRequest(OperatorSignalType.CANCEL, "stop", null, null, null);

        assertThatThrownBy(() -> service.signal(PROJECT, WORKFLOW_ID, request))
                .isInstanceOf(AuthorizationException.class);

        // A denied attempt is recorded (with a null runId — the port was never described) and the
        // control port is never touched, so an unauthorized caller learns nothing about the execution.
        verify(auditRecorder)
                .write(eq("anonymous"), eq(PROJECT), eq(WORKFLOW_ID), eq(null), any(), eq(AuthorizationOutcome.DENIED));
        verify(port, never()).describe(any());
        verify(port, never()).signal(any(), any());
    }

    @Test
    void signalDeniesWhenNoActorIsPresent() {
        ActorHolder.clear(); // no authenticated actor at all
        var request = new SignalRequest(OperatorSignalType.CANCEL, "stop", null, null, null);

        assertThatThrownBy(() -> service.signal(PROJECT, WORKFLOW_ID, request))
                .isInstanceOf(AuthorizationException.class);

        verify(auditRecorder)
                .write(eq("anonymous"), eq(PROJECT), eq(WORKFLOW_ID), eq(null), any(), eq(AuthorizationOutcome.DENIED));
        verify(port, never()).signal(any(), any());
    }

    @Test
    void operatorSignalContractVersionIsStable() {
        // The audit trail is keyed to this version; a bump is a deliberate contract change.
        assertThat(OperatorSignalContract.VERSION).isEqualTo("gc.workflow.implement-signals.v1");
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
                new WorkflowExecutionView.Correlation(PROJECT, 1278, List.of("GC-O009")),
                null);
    }
}
