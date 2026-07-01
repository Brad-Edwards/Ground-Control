package com.keplerops.groundcontrol.domain.controlidentification.state;

/**
 * Why a mapping rule fired for a threat but produced no candidate control (GC-GRC-008 clause d).
 * Gaps are explicit control-design work surfaced for a human/agent — never silently dropped.
 */
public enum ControlIdentificationGapReason {
    /** No available control's framework identifiers matched the rule's selectors. */
    NO_MATCHING_CONTROL,
    /** The project has no available controls at all (no installed packs, no project controls). */
    NO_CONTROLS_AVAILABLE
}
