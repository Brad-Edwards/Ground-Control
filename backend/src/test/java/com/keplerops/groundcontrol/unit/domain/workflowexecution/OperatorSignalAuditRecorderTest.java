package com.keplerops.groundcontrol.unit.domain.workflowexecution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.keplerops.groundcontrol.domain.workflowexecution.OperatorSignalType;
import com.keplerops.groundcontrol.domain.workflowexecution.Reviewer;
import com.keplerops.groundcontrol.domain.workflowexecution.SignalDisposition;
import com.keplerops.groundcontrol.domain.workflowexecution.audit.AuthorizationOutcome;
import com.keplerops.groundcontrol.domain.workflowexecution.audit.OperatorSignalAudit;
import com.keplerops.groundcontrol.domain.workflowexecution.audit.OperatorSignalAuditRecorder;
import com.keplerops.groundcontrol.domain.workflowexecution.audit.OperatorSignalAuditRepository;
import com.keplerops.groundcontrol.domain.workflowexecution.service.SendSignalCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OperatorSignalAuditRecorderTest {

    private final OperatorSignalAuditRepository repository = mock(OperatorSignalAuditRepository.class);
    private final OperatorSignalAuditRecorder recorder = new OperatorSignalAuditRecorder(repository);

    @Test
    void stampsContractVersionAndCopiesSignalFields() {
        var command = new SendSignalCommand(
                OperatorSignalType.REVIEW_CAP_DISPOSITION, null, null, Reviewer.CODEX, SignalDisposition.PROCEED);

        recorder.record("actor-1", "proj", "gc-implement-proj-42", "run-9", command, AuthorizationOutcome.ALLOWED);

        var captor = ArgumentCaptor.forClass(OperatorSignalAudit.class);
        verify(repository).save(captor.capture());
        OperatorSignalAudit saved = captor.getValue();
        assertThat(saved.getActor()).isEqualTo("actor-1");
        assertThat(saved.getProject()).isEqualTo("proj");
        assertThat(saved.getWorkflowId()).isEqualTo("gc-implement-proj-42");
        assertThat(saved.getRunId()).isEqualTo("run-9");
        assertThat(saved.getSignalType()).isEqualTo(OperatorSignalType.REVIEW_CAP_DISPOSITION);
        assertThat(saved.getReviewer()).isEqualTo(Reviewer.CODEX);
        assertThat(saved.getDisposition()).isEqualTo(SignalDisposition.PROCEED);
        assertThat(saved.getAuthorizationOutcome()).isEqualTo(AuthorizationOutcome.ALLOWED);
        assertThat(saved.getContractVersion()).isEqualTo("gc.workflow.implement-signals.v1");
    }

    @Test
    void truncatesOverlongReasonToColumnBound() {
        String longReason = "x".repeat(OperatorSignalAudit.MAX_REASON_LENGTH + 500);
        var command = new SendSignalCommand(OperatorSignalType.CANCEL, longReason, null, null, null);

        recorder.record("actor-1", "proj", "gc-implement-proj-42", null, command, AuthorizationOutcome.DENIED);

        var captor = ArgumentCaptor.forClass(OperatorSignalAudit.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getReason()).hasSize(OperatorSignalAudit.MAX_REASON_LENGTH);
        assertThat(captor.getValue().getRunId()).isNull();
    }
}
