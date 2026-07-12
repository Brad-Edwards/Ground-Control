package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

import com.fasterxml.jackson.annotation.JsonValue;

/** Codex review verdict envelope value (ADR-029 #931 verdict model). */
public enum ReviewVerdict {
    SHIP("ship"),
    SHIP_WITH_FIXES("ship-with-fixes"),
    DONT_SHIP("dont-ship");

    private final String wire;

    ReviewVerdict(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
