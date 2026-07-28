package com.keplerops.groundcontrol.domain.workflowtelemetry;

/**
 * The verdict of the thing a station inspected (ADR-090 section 3, issue #1355).
 *
 * <p>This is the only axis that feeds yield and rework formulas, and it is deliberately disjoint
 * from {@link PhaseEventType} and {@link WorkflowRunState}: they share no value, so no aggregate
 * can read a phase completing, a tool succeeding, or a pull request merging as a gate passing.
 *
 * <p>A producer states its result explicitly. Nothing here is ever derived from {@code COMPLETED},
 * from the free-text {@code outcome}, or from the absence of a later failure.
 */
public enum StationResult {
    /** The gate inspected the change and found no problem. */
    PASS,

    /** The gate inspected the change and rejected it. Only this and {@link #PASS} are evaluable. */
    FAIL,

    /**
     * The gate was deliberately not run for this attempt — a repo with no SonarCloud block, for
     * example. Coverage, not a pass: counting it as one would inflate yield with runs nothing
     * inspected.
     */
    SKIPPED_STATION,

    /** The attempt was abandoned before it could reach a verdict. */
    CANCELLED,

    /**
     * The gate ran but no verdict could be observed: an outage, a parser error, a timeout. Kept
     * separate from {@link #FAIL} so an infrastructure problem never enters the rework signal as a
     * defect in the change.
     */
    NOT_EVALUABLE,

    /**
     * No result was ever recorded. The default for every row written before the axis existed, and
     * for any emitter that cannot attest a verdict. Excluded from formula denominators rather than
     * counted as a pass.
     */
    UNOBSERVED
}
