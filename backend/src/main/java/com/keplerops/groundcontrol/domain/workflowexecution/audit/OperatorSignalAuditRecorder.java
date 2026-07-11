package com.keplerops.groundcontrol.domain.workflowexecution.audit;

import com.keplerops.groundcontrol.domain.workflowexecution.OperatorSignalContract;
import com.keplerops.groundcontrol.domain.workflowexecution.service.SendSignalCommand;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the append-only {@link OperatorSignalAudit} rows for operator-signal attempts (GC-O009 (b)).
 *
 * <p>Each write runs in its own {@link Propagation#REQUIRES_NEW} transaction so the audit row commits
 * independently of the caller's transaction. This is load-bearing for the <em>denied</em> path: the
 * control service records the denied attempt and then throws {@code AuthorizationException}, which would
 * roll back a shared transaction — the audit trail must survive that rollback, because a denied attempt
 * is exactly what the gate-authority trail must not lose.
 */
@Component
public class OperatorSignalAuditRecorder {

    private final OperatorSignalAuditRepository repository;

    public OperatorSignalAuditRecorder(OperatorSignalAuditRepository repository) {
        this.repository = repository;
    }

    /** Record one operator-signal attempt (allowed or denied) as an independent, committed audit row. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(
            String actor,
            String project,
            String workflowId,
            String runId,
            SendSignalCommand command,
            AuthorizationOutcome outcome) {
        repository.save(OperatorSignalAudit.of(
                actor, project, workflowId, runId, OperatorSignalContract.VERSION, outcome, command));
    }
}
