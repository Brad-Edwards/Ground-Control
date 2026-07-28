package com.keplerops.groundcontrol.domain.workflowtelemetry;

/**
 * What became of a finding a station observed (issue #1355).
 *
 * <p>Detection and disposition are different moments. A newly detected finding is {@link #OPEN};
 * the terminal values are ADR-029's decision outcomes expressed as measurement facts, reusing that
 * vocabulary rather than growing a second one. {@code defer} is absent by construction: ADR-029
 * forbids deferral, so the projection must not be able to express it.
 *
 * <p>The review-cycle wrapper posts {@code decision: fix} before the agent has repaired anything.
 * That is intent, not proof, and must never arrive here as {@link #FIXED}.
 */
public enum FindingDisposition {
    /** Observed, not yet resolved. A missing disposition is this — never {@link #NOT_APPLICABLE}. */
    OPEN,

    /** Repaired, attested by a later tool-layer boundary that can prove it. */
    FIXED,

    /** Accepted as-is. Inherits ADR-029's explicit user-authorization requirement. */
    WONTFIX,

    /** The reported condition is factually false or does not apply. Requires a rationale. */
    NOT_APPLICABLE;

    /**
     * Terminal states are final. A finding moves from {@link #OPEN} to exactly one of them and
     * never moves again, so a redelivered observation cannot reopen a resolved finding and two
     * conflicting terminal claims surface as an error rather than a silent last-write-wins.
     */
    public boolean isTerminal() {
        return this != OPEN;
    }

    /**
     * Whether reaching this disposition must name where it was authorized.
     *
     * <p>True for the two values that retire a finding without repairing it. Both remove it from the
     * escape-rate signal on an assertion no gate re-run can check, so the assertion has to be
     * attributable. {@link #FIXED} is excluded because the station's next attempt is its evidence.
     */
    public boolean requiresAuthorization() {
        return this == WONTFIX || this == NOT_APPLICABLE;
    }
}
