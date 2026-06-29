package com.keplerops.groundcontrol.domain.dataclassification.state;

/**
 * Deterministic reason codes for data-classification lattice evaluation findings (GC-GRC-006).
 *
 * <p>{@link #LABEL_FLOW_NOT_PERMITTED} is a policy violation: a labeled flow whose
 * (source-label, sink-label) pair is absent from the permitted-flow relation. Every other reason is
 * a limitation — an input the lattice cannot decide on (a missing or unknown label, or a flow whose
 * endpoints are not present in the evaluated snapshot). Limitations are surfaced explicitly rather
 * than allowed to pass silently, per the requirement that missing classification is at least a gap.
 */
public enum DataClassificationFindingReason {
    LABEL_FLOW_NOT_PERMITTED(true),
    MISSING_SOURCE_LABEL(false),
    MISSING_SINK_LABEL(false),
    UNKNOWN_SOURCE_LABEL(false),
    UNKNOWN_SINK_LABEL(false),
    DANGLING_FLOW_ENDPOINT(false);

    private final boolean violation;

    DataClassificationFindingReason(boolean violation) {
        this.violation = violation;
    }

    /** True when this reason represents a policy violation; false when it is a limitation. */
    public boolean isViolation() {
        return violation;
    }
}
