package com.keplerops.groundcontrol.domain.workflowexecution.audit;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.workflowexecution.OperatorSignalType;
import com.keplerops.groundcontrol.domain.workflowexecution.RetryPhase;
import com.keplerops.groundcontrol.domain.workflowexecution.Reviewer;
import com.keplerops.groundcontrol.domain.workflowexecution.SignalDisposition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Append-only audit record of one operator-signal attempt against a workflow execution (GC-O009 (b),
 * GC-P024, GC-Q016 (b)). Because a Temporal signal is not a JPA entity mutation, Temporal history and
 * Envers cannot on their own carry the gate-authority trail the requirement demands — every attempt,
 * <em>allowed or denied</em>, is recorded here so "who tried to act on which gate, with what authority,
 * when" is queryable independently of workflow history.
 *
 * <p>Append-only: rows are inserted, never updated. This is not an {@code @Audited} entity — it <em>is</em>
 * the audit log, so it has no Envers {@code _audit} companion.
 *
 * <p>Closed, redacted field set. Only safe correlation and decision facts are stored: actor id,
 * project, workflow/run id, signal type, contract version, authorization outcome, and the bounded
 * signal-specific fields. GitHub tokens, bearer tokens, prompts, completions, and raw request payloads
 * are never persisted.
 */
@Entity
@Table(name = "operator_signal_audit")
public class OperatorSignalAudit extends BaseEntity {

    /** Reason free-text is bounded so an oversized caller string can never overflow the column. */
    public static final int MAX_REASON_LENGTH = 1000;

    @Column(nullable = false, length = 200)
    private String actor;

    @Column(nullable = false, length = 200)
    private String project;

    @Column(name = "workflow_id", nullable = false, length = 500)
    private String workflowId;

    @Column(name = "run_id", length = 200)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, length = 40)
    private OperatorSignalType signalType;

    @Column(name = "contract_version", nullable = false, length = 100)
    private String contractVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "authorization_outcome", nullable = false, length = 20)
    private AuthorizationOutcome authorizationOutcome;

    @Column(length = MAX_REASON_LENGTH)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "retry_from_phase", length = 40)
    private RetryPhase retryFromPhase;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private Reviewer reviewer;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private SignalDisposition disposition;

    protected OperatorSignalAudit() {
        // JPA
    }

    private OperatorSignalAudit(
            String actor,
            String project,
            String workflowId,
            String runId,
            OperatorSignalType signalType,
            String contractVersion,
            AuthorizationOutcome authorizationOutcome,
            String reason,
            RetryPhase retryFromPhase,
            Reviewer reviewer,
            SignalDisposition disposition) {
        this.actor = actor;
        this.project = project;
        this.workflowId = workflowId;
        this.runId = runId;
        this.signalType = signalType;
        this.contractVersion = contractVersion;
        this.authorizationOutcome = authorizationOutcome;
        this.reason = truncateReason(reason);
        this.retryFromPhase = retryFromPhase;
        this.reviewer = reviewer;
        this.disposition = disposition;
    }

    /** Build an audit row; {@code reason} is truncated to {@link #MAX_REASON_LENGTH} to fit the column. */
    public static OperatorSignalAudit record(
            String actor,
            String project,
            String workflowId,
            String runId,
            OperatorSignalType signalType,
            String contractVersion,
            AuthorizationOutcome authorizationOutcome,
            String reason,
            RetryPhase retryFromPhase,
            Reviewer reviewer,
            SignalDisposition disposition) {
        return new OperatorSignalAudit(
                actor,
                project,
                workflowId,
                runId,
                signalType,
                contractVersion,
                authorizationOutcome,
                reason,
                retryFromPhase,
                reviewer,
                disposition);
    }

    private static String truncateReason(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }

    public String getActor() {
        return actor;
    }

    public String getProject() {
        return project;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getRunId() {
        return runId;
    }

    public OperatorSignalType getSignalType() {
        return signalType;
    }

    public String getContractVersion() {
        return contractVersion;
    }

    public AuthorizationOutcome getAuthorizationOutcome() {
        return authorizationOutcome;
    }

    public String getReason() {
        return reason;
    }

    public RetryPhase getRetryFromPhase() {
        return retryFromPhase;
    }

    public Reviewer getReviewer() {
        return reviewer;
    }

    public SignalDisposition getDisposition() {
        return disposition;
    }
}
