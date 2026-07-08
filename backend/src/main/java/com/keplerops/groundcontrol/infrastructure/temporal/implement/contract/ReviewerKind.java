package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

import com.fasterxml.jackson.annotation.JsonValue;

/** Reviewer a review-cap disposition signal applies to (GC-O007 review loop). */
public enum ReviewerKind {
    CODEX("codex"),
    TEST_QUALITY("test-quality");

    private final String wire;

    ReviewerKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
