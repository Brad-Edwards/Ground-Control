package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

import com.fasterxml.jackson.annotation.JsonValue;

/** Review-cap-boundary disposition (GC-O007 / issue #1245): proceed, one over-cap cycle, or escalate. */
public enum CapDisposition {
    PROCEED("proceed"),
    ONE_MORE_CYCLE("one_more_cycle"),
    ESCALATE_TO_HUMAN("escalate_to_human");

    private final String wire;

    CapDisposition(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
