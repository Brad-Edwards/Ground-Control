package com.keplerops.groundcontrol.infrastructure.temporal.implement;

import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CancelSignal;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.GateState;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementOutcome;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementPhase;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementWorkflowInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementWorkflowResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.RetryFromSignal;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReviewCapDispositionSignal;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Deterministic core {@code /implement} workflow (GC-O009 phase 2). Orchestrates the GC-O007/ADR-029
 * phase graph A-E over typed deterministic activities and the content-activity seam, preserving the
 * gate order exactly (no gate added, none weakened). PR merge is the single synchronous human gate,
 * observed from GitHub as the authoritative event — never a signal.
 *
 * <p>Signals form a closed operator catalog; the REST/MCP endpoints that send them arrive in later
 * program phases (#1278/#1279). Their workflow-side handling is defined and tested here.
 */
@WorkflowInterface
public interface ImplementWorkflow {

    @WorkflowMethod
    ImplementWorkflowResult run(ImplementWorkflowInput input);

    /** Cancel the run; the workflow finishes with {@link ImplementOutcome#CANCELLED} at its next await point. */
    @SignalMethod
    void cancel(CancelSignal signal);

    /** Authorize a retry of the escalated gate. The phase field is recorded for audit. */
    @SignalMethod
    void retryFrom(RetryFromSignal signal);

    /** Apply a review-cap-boundary disposition for the current review gate (GC-O007 / issue #1245). */
    @SignalMethod
    void applyReviewCapDisposition(ReviewCapDispositionSignal signal);

    /** Current execution phase (query). */
    @QueryMethod
    ImplementPhase currentPhase();

    /** Terminal outcome, or {@code null} while the run is still in progress (query). */
    @QueryMethod
    ImplementOutcome currentOutcome();

    /**
     * Bounded gate-state read model (GC-O009 (c), GC-Q016): current phase, whether the run is blocked on
     * the single human merge gate, and which gate — if any — is escalated awaiting an operator signal.
     * The product read model derives from this query, not from raw Temporal history.
     */
    @QueryMethod
    GateState gateState();
}
